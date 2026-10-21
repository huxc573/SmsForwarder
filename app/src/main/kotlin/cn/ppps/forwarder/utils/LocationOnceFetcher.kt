package cn.ppps.forwarder.utils

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.LocationInfo
import com.xuexiang.xaop.util.PermissionUtils
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 按需定位：需要坐标时取一次并缓存，客户端短暂等待。
 *
 * ## 两条预算，互不绑定
 * 「客户端愿意等多久」和「定位需要做多久」是两件事：
 *
 *  - **客户端等待预算** [clientWaitMs]：HTTP 请求能撑住的上限，取
 *    `min(设计总超时, requestTimeout - 余量, 6s)`。超了就先把当前缓存回给客户端，
 *    绝不拖到客户端自己超时报「网络连接超时」。
 *  - **定位预算** [serverBudgetMs]：由「电耗」档位决定（低 13s / 中 20s / 高 28s /
 *    不要求 38s）。即使客户端已经走了，后台仍把这轮定位做完并写入缓存。
 *
 * 结果由**监听器**直接写 `apiLocationCache`，不依赖 [fetchIfNeeded] 的返回值。
 * 这样即使客户端早已返回，后台定位成功的结果也不会丢——下次刷新直接命中。
 *
 * ## 精度与电耗的分工
 *  - 精度 = 用谁：精确 → 必须等 GPS；模糊 → 只用网络，绝不点亮 GNSS；
 *    不要求 → 网络够用，GPS 在后台顺带升级。
 *  - 电耗 = 等多久：只决定 [LocationProviderPicker.gpsWaitTimeoutMs]。
 *
 * 精度不再参与「缓存能不能用」的判定。缓存只用时间判断新旧：精度只影响
 * **重新定位时选谁**，不影响**已有坐标能不能先给用户看**。否则室内（只有网络
 * 结果）会导致缓存永远不可用，每次刷新都重新开 GPS，比不缓存更费电。
 *
 * ## 为什么不用 LocationClient
 * 上游 `App.LocationClient` 是单例，同一时刻只能持有一个请求，且回调走主线程 Looper。
 *  1. GPS 和网络无法并行，只能「等完 GPS 再问网络」，室内白等到超时才降级；
 *  2. 调用方一旦阻塞主线程，回调送不到、`onStartCommand` 也派发不出去，形成自锁。
 * 所以直接用系统 `LocationManager`，回调注册在**自有后台线程**上。
 */
object LocationOnceFetcher {

    private const val LOG_TAG = "LocationOnceFetcher"

    /** 新鲜度下限，避免间隔设置过小时每条短信都打一次 GPS */
    private const val MIN_FRESH_SEC = 60

    /** 客户端等待上限，超过就先回缓存，后台继续 */
    private const val CLIENT_WAIT_CAP_MS = 6_000L

    /** 非交互场景（转发打标签）的客户端等待上限：宁可用旧坐标，也不要卡住转发 */
    private const val TAG_WAIT_MS = 4_000L

    /** 留给 HTTP 层序列化与回包的余量 */
    private const val RESPONSE_MARGIN_MS = 1_500L

    /** 客户端等待下限，太短等于必定拿不到 */
    private const val MIN_CLIENT_WAIT_MS = 1_500L

    /** 已经拿到网络结果后，还愿意为 GPS 升级等多久（再长就是纯粹耗电） */
    private const val UPGRADE_MAX_MS = 8_000L

    /** GPS 等待期结束后，留给网络回包的时间 */
    private const val NETWORK_TAIL_MS = 3_000L

    /** 定位回调专用后台线程 */
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val gate = Any()
    private var current: Session? = null

    /** 一次在途定位请求 */
    private class Session {
        val done = CountDownLatch(1)

        @Volatile
        var best: Location? = null

        @Volatile
        var started = false

        @Volatile
        var stopped = false

        @Volatile
        var delivered = false

        @Volatile
        var upgradeScheduled = false
    }

    /**
     * 取一次位置。
     *
     * @param interactive true = 用户在客户端主动刷新（远程找手机）；
     *                    false = 转发内容替换 `{{定位信息}}`。
     */
    fun fetchIfNeeded(context: Context, interactive: Boolean = true): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache
        val cachedAt = HttpServerUtils.apiLocationCacheAt

        //定位总开关关着，或者没开按需模式：保持上游行为，直接给缓存
        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return cached

        //缓存可用就直接用（连点刷新、刚定位过的场景）
        if (isUsable(cached)) return cached

        if (!canLocate(context)) {
            Log.w(LOG_TAG, "无法定位：权限或系统定位开关不满足")
            return cached
        }

        val session = joinOrCreate()
        ensureStarted(context, session)

        val wait = clientWaitMs(context, interactive)
        val finished = try {
            session.done.await(wait, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }

        //结果由监听器写入缓存，这里统一读缓存
        val latest = HttpServerUtils.apiLocationCache
        if (hasCoord(latest) && HttpServerUtils.apiLocationCacheAt > cachedAt) {
            Log.i(LOG_TAG, "返回 provider=${latest.provider}（等待 ${wait}ms，本轮${if (finished) "已结束" else "仍在后台进行"}）")
            return latest
        }
        if (finished) {
            Log.w(LOG_TAG, "本轮定位未取到结果，返回缓存 provider=${cached.provider}")
        } else {
            //关键：客户端先返回，但定位不取消，后台继续跑完写缓存
            Log.i(LOG_TAG, "客户端等待 ${wait}ms 超时，定位转入后台继续，先返回缓存")
        }
        return cached
    }

    /**
     * 只发起、不等待。
     */
    fun requestAsync(context: Context) {
        if (!SettingUtils.enableLocation) return
        if (!canLocate(context)) return
        ensureStarted(context, joinOrCreate())
    }

    private fun joinOrCreate(): Session = synchronized(gate) {
        current ?: Session().also { current = it }
    }

    private fun ensureStarted(context: Context, session: Session) {
        synchronized(gate) {
            if (session.started || session.stopped || current !== session) return
            session.started = true
        }
        startAsync(context, session, serverBudgetMs())
    }

    private fun handlerOrNew(): Handler = synchronized(this) {
        val t = thread
        val h = handler
        if (t != null && t.isAlive && h != null) return h
        val nt = HandlerThread("sf-one-shot-location").also { it.start() }
        thread = nt
        Handler(nt.looper).also { handler = it }
    }

    /**
     * 在后台线程并行注册 GPS + 网络。
     *
     * 时间轴（以「不要求 / 不要求」为例）：
     * ```
     * 0ms    注册 gps + network
     * ~1s    network 到 → 写缓存 + 交付客户端，同时安排 8s 后收手
     * ~9s    仍未收手则 removeUpdates（GPS 熄灭）
     * 38s    兜底 removeUpdates
     * ```
     * 精确模式下时间轴不同：network 先到只暂存、不交付，
     * 到 gpsWait 仍未拿到 GPS 才降级交付并立刻收手。
     */
    private fun startAsync(context: Context, session: Session, budget: Long) {
        val h = handlerOrNew()
        h.post {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                finish(session)
                return@post
            }

            val coarseOnly = LocationProviderPicker.coarseOnly()
            val needsGps = LocationProviderPicker.needsGps()
            val startedAt = SystemClock.elapsedRealtime()

            //GPS 允许占用多久：不超过预算，且给网络留出兜底时间
            val gpsWait = if (coarseOnly) 0L else
                minOf(LocationProviderPicker.gpsWaitTimeoutMs(), (budget - NETWORK_TAIL_MS).coerceAtLeast(0L))

            /**
             * 现在这个结果能不能拿去用。
             *  - GPS 结果：随时可用
             *  - 模糊：网络即可
             *  - 不要求：网络即可（先用网络的顶上，GPS 到了再升级）
             *  - 精确：网络要等 GPS 等待期结束才算数，避免过早用低精度结果
             */
            fun acceptable(location: Location): Boolean {
                if (location.provider == LocationManager.GPS_PROVIDER) return true
                if (coarseOnly) return true
                if (!needsGps) return true
                return SystemClock.elapsedRealtime() - startedAt >= gpsWait
            }

            /**
             * 拿到可用结果后是否直接收手。
             * 只有「不要求」会继续监听，等 GPS 把缓存升级成更准的坐标。
             */
            fun shouldStop(location: Location): Boolean {
                if (location.provider == LocationManager.GPS_PROVIDER) return true
                if (coarseOnly) return true
                if (needsGps) return true
                return false
            }

            /** 写缓存 + 交付。缓存时间戳单独记，避免 provider 给旧定位点时缓存立刻失效 */
            fun publish(location: Location) {
                val info = LocationInfo(
                    location.longitude,
                    location.latitude,
                    "",
                    App.DateFormat.format(Date(location.time)),
                    location.provider.toString()
                )
                try {
                    val list = App.Geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (list?.isNotEmpty() == true) info.address = list[0].getAddressLine(0)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "逆地理编码失败：${e.message}")
                }
                HttpServerUtils.apiLocationCache = info
                HttpServerUtils.apiLocationCacheAt = System.currentTimeMillis()
                deliver(session)
            }

            lateinit var listener: LocationListener

            /**
             * 网络结果已在手、GPS 还在等：再给 [UPGRADE_MAX_MS] 就收手。
             * 不做这一步，「不要求」模式每次定位都会白挂满 38 秒 GNSS。
             */
            fun scheduleUpgradeStop() {
                if (session.upgradeScheduled || session.stopped) return
                session.upgradeScheduled = true
                Log.i(LOG_TAG, "已有网络结果，GPS 最多再等 ${UPGRADE_MAX_MS}ms 用于升级")
                h.postDelayed({ stopAll(lm, listener, session) }, UPGRADE_MAX_MS)
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    //结果只进不退：已有 GPS 时不再被网络结果覆盖
                    val old = session.best
                    if (old != null && old.provider == LocationManager.GPS_PROVIDER &&
                        location.provider != LocationManager.GPS_PROVIDER
                    ) return

                    if (!acceptable(location)) {
                        //先记下，等 GPS 等待期结束再统一决定要不要降级用它
                        session.best = location
                        return
                    }
                    session.best = location
                    publish(location)
                    if (shouldStop(location)) {
                        Log.i(LOG_TAG, "命中 provider=${location.provider}，结束本轮")
                        stopAll(lm, listener, session)
                    } else {
                        Log.i(LOG_TAG, "先返回 provider=${location.provider}，继续等 GPS 升级")
                        scheduleUpgradeStop()
                    }
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            val candidates = mutableListOf<String>()
            if (!coarseOnly && LocationProviderPicker.isReady(context, LocationManager.GPS_PROVIDER)) {
                candidates.add(LocationManager.GPS_PROVIDER)
            }
            if (LocationProviderPicker.isReady(context, LocationManager.NETWORK_PROVIDER)) {
                candidates.add(LocationManager.NETWORK_PROVIDER)
            }
            if (candidates.isEmpty()) {
                Log.w(LOG_TAG, "无可用 provider（GPS/网络均不可用）")
                finish(session)
                return@post
            }

            try {
                candidates.forEach { p ->
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(p, 0L, 0f, listener, h.looper)
                }
                Log.i(LOG_TAG, "发起定位 providers=$candidates gpsWait=${gpsWait}ms budget=${budget}ms")
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "请求定位失败：${e.message}")
                finish(session)
                return@post
            }

            //GPS 等待期结束：还没交付过，就用手上最好的结果降级，别空手而归
            if (gpsWait > 0L) {
                h.postDelayed({
                    if (session.stopped || session.delivered) return@postDelayed
                    val best = session.best
                    if (best != null) {
                        Log.i(LOG_TAG, "GPS 等待 ${gpsWait}ms 到点未出结果，降级使用 ${best.provider}")
                        session.best = best
                        publish(best)
                        if (needsGps) {
                            //精确：已经等够约定时间，拿网络结果收手，不再挂着 GNSS
                            stopAll(lm, listener, session)
                        } else {
                            scheduleUpgradeStop()
                        }
                    } else {
                        Log.w(LOG_TAG, "GPS 等待到点，网络结果也还没到，继续等到预算结束")
                    }
                }, gpsWait)
            }

            //总预算兜底，避免任何路径下 GPS 一直挂着耗电
            h.postDelayed({ stopAll(lm, listener, session) }, budget)
        }
    }

    private fun stopAll(lm: LocationManager, listener: LocationListener, session: Session) {
        synchronized(session) {
            if (session.stopped) return
            session.stopped = true
        }
        try {
            @Suppress("DEPRECATION")
            lm.removeUpdates(listener)
            Log.i(LOG_TAG, "已注销定位监听")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "removeUpdates 失败：${e.message}")
        }
        finish(session)
    }

    /** 交付结果给等待方（只通知一次，后续 GPS 升级不再重复通知） */
    private fun deliver(session: Session) {
        synchronized(session) {
            if (session.delivered) return
            session.delivered = true
        }
        session.done.countDown()
    }

    private fun finish(session: Session) {
        synchronized(gate) {
            if (current === session) current = null
        }
        deliver(session)
    }

    /**
     * 客户端等待预算：永远留余量给 HTTP 回包，并且不超过 6 秒。
     */
    private fun clientWaitMs(context: Context, interactive: Boolean): Long {
        val designed = LocationProviderPicker.totalTimeoutMs()
        if (!interactive) return minOf(TAG_WAIT_MS, designed)
        val clientMs = SettingUtils.requestTimeout.toLong() * 1000L
        return maxOf(
            minOf(designed, clientMs - RESPONSE_MARGIN_MS, CLIENT_WAIT_CAP_MS),
            MIN_CLIENT_WAIT_MS
        )
    }

    /**
     * 定位预算：由「电耗」档位决定，与客户端等待无关。
     * 客户端先返回后，后台仍按这个预算跑完。
     */
    private fun serverBudgetMs(): Long = LocationProviderPicker.totalTimeoutMs()

    private fun canLocate(context: Context): Boolean = try {
        LocationUtils.isLocationEnabled(context) && LocationUtils.hasLocationCapability(context) &&
            PermissionUtils.isGranted(
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
    } catch (e: Exception) {
        false
    }

    private fun hasCoord(info: LocationInfo): Boolean =
        info.longitude != 0.0 || info.latitude != 0.0

    /**
     * 缓存是否可直接使用：有坐标 + 未过期。
     *
     * 这里**不校验 provider**：精度只决定「重新定位时用谁」，
     * 不决定「已有坐标能不能先用」。否则室内只有网络结果时缓存永远不可用，
     * 每次刷新都会重新唤醒 GNSS，比不缓存更费电。
     */
    private fun isUsable(info: LocationInfo): Boolean {
        if (!hasCoord(info)) return false
        if (info.time.isEmpty()) return false
        return isFresh(info)
    }

    /**
     * 是否未超过「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）。
     *
     * 用**写入时刻**而不是定位时间：网络 provider 首次回调常常给一个很旧的
     * 缓存点，若按定位时间算，缓存写进去就立刻"过期"，下次刷新又要重定位。
     */
    private fun isFresh(info: LocationInfo): Boolean {
        val freshMs = maxOf(SettingUtils.locationMinInterval, MIN_FRESH_SEC * 1000L)
        val at = HttpServerUtils.apiLocationCacheAt
        val age = if (at > 0L) {
            System.currentTimeMillis() - at
        } else {
            //兼容旧版本写入的缓存（没有写入时刻），退回按定位时间判断
            try {
                System.currentTimeMillis() - (App.DateFormat.parse(info.time)?.time ?: return false)
            } catch (e: Exception) {
                return false
            }
        }
        return age < freshMs
    }
}
