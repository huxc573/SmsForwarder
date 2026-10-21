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
 * 「客户端愿意等多久」和「定位需要做多久」是两件事，必须分开：
 *
 *  - **客户端等待预算** `clientWaitMs()`：HTTP 请求能撑住的上限，取
 *    `min(设计总超时, requestTimeout - 余量, 6s)`。超了就先把当前缓存回给客户端，
 *    绝不拖到客户端自己超时报「网络连接超时」。
 *  - **定位预算** `serverBudgetMs()`：由「电耗」档位决定（低 13s / 中 20s / 高 28s /
 *    不要求 38s）。即使客户端已经走了，后台仍把这轮定位做完并写入缓存。
 *
 * 这样室内第一次点刷新通常 1~3 秒就能拿到网络定位；信号差时客户端先返回，
 * 后台继续跑完，用户再点一次刷新就是秒开缓存。
 *
 * ## 为什么不用 LocationClient
 * 上游 `App.LocationClient` 是单例，同一时刻只能持有一个请求，且回调走主线程 Looper。
 *  1. GPS 和网络无法并行，只能「等完 GPS 再问网络」，室内白等到超时才降级；
 *  2. 调用方一旦阻塞主线程，回调送不到、`onStartCommand` 也派发不出去，形成自锁。
 * 所以直接用系统 `LocationManager`，回调注册在**自有后台线程**上。
 *
 * ## 缓存写入
 * 结果由**监听器**直接写 `apiLocationCache`，不依赖 `fetchIfNeeded` 的返回值。
 * 这样即使客户端早已返回，后台定位成功的结果也不会丢——下次刷新直接命中。
 *
 * ## 缓存可用性（三段，缺一不可）
 *  1. 有坐标 —— 经纬度非 0
 *  2. 未过期 —— 距今不超过「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）
 *  3. 精度达标 —— 精确 / 不要求模式下缓存必须是 GPS 结果
 */
object LocationOnceFetcher {

    private const val LOG_TAG = "LocationOnceFetcher"

    /** 新鲜度下限，避免间隔设置过小时每条短信都打一次 GPS */
    private const val MIN_FRESH_SEC = 60

    /** 客户端等待上限，超过就先回缓存，后台继续 */
    private const val CLIENT_WAIT_CAP_MS = 6_000L

    /** 非交互场景（转发打标签）的客户端等待上限 */
    private const val TAG_WAIT_MS = 8_000L

    /** 留给 HTTP 层序列化与回包的余量 */
    private const val RESPONSE_MARGIN_MS = 1_500L

    /** 客户端等待下限，太短等于必定拿不到 */
    private const val MIN_CLIENT_WAIT_MS = 1_500L

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
    }

    /**
     * 取一次位置。
     *
     * @param interactive true = 用户在客户端主动刷新（远程找手机）；
     *                    false = 转发内容替换 `{{定位信息}}`。
     */
    fun fetchIfNeeded(context: Context, interactive: Boolean = true): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache

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
        if (hasCoord(latest) && latest.time != cached.time) {
            Log.i(LOG_TAG, "返回 provider=${latest.provider}（客户端等待 ${wait}ms，本轮${if (finished) "已结束" else "仍在后台进行"}）")
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
            //愿意为 GPS 等多久：受定位预算约束，且不能吃掉全部预算
            val gpsWait = if (coarseOnly) 0L else
                minOf(LocationProviderPicker.gpsWaitTimeoutMs(), maxOf(budget - RESPONSE_MARGIN_MS * 2, 0L))
            val startedAt = SystemClock.elapsedRealtime()

            val needsGps = LocationProviderPicker.needsGps()

            /**
             * 现在能不能拿去用。
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
             * 拿到可用结果后是否该收手。
             * 「不要求」模式下网络结果先交付，但继续保持 GPS 监听，
             * 等 GPS 在预算内到达时把缓存升级成更准的坐标。
             */
            fun shouldStop(location: Location): Boolean {
                if (location.provider == LocationManager.GPS_PROVIDER) return true
                if (coarseOnly) return true
                if (needsGps) return true
                return false
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    //结果只进不退：已有 GPS 时不再被网络结果覆盖
                    val old = session.best
                    if (old != null && old.provider == LocationManager.GPS_PROVIDER &&
                        location.provider != LocationManager.GPS_PROVIDER
                    ) return
                    if (!acceptable(location)) {
                        //先记下，但不交付、不写缓存
                        session.best = location
                        return
                    }
                    session.best = location
                    //直接写缓存：客户端即使已经走了，这个结果也不会丢
                    HttpServerUtils.apiLocationCache = toInfo(location)
                    deliver(session)
                    if (shouldStop(location)) {
                        Log.i(LOG_TAG, "命中 provider=${location.provider}，结束本轮")
                        stopAll(lm, this, session)
                    } else {
                        Log.i(LOG_TAG, "先返回 provider=${location.provider}，继续等 GPS 升级")
                    }
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            val providers = mutableListOf<String>()
            if (!coarseOnly && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER)
            }
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER)
            }
            if (providers.isEmpty()) {
                Log.w(LOG_TAG, "无可用 provider")
                finish(session)
                return@post
            }

            try {
                providers.forEach { p ->
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(p, 0L, 0f, listener, h.looper)
                }
                Log.i(LOG_TAG, "发起定位 providers=$providers gpsWait=${gpsWait}ms budget=${budget}ms")
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "请求定位失败：${e.message}")
                finish(session)
                return@post
            }

            //到点收手，避免 GPS 长时间挂着耗电
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
        } catch (e: Exception) {
            Log.w(LOG_TAG, "removeUpdates 失败：${e.message}")
        }
        finish(session)
    }

    /** 交付结果给等待方（只通知一次，后续 GPS 升级不再重复通知） */
    private fun deliver(session: Session) {
        if (session.delivered) return
        session.delivered = true
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
        if (!interactive) return TAG_WAIT_MS
        val clientMs = SettingUtils.requestTimeout.toLong() * 1000L
        val designed = LocationProviderPicker.totalTimeoutMs()
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

    private fun toInfo(location: Location): LocationInfo {
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
        return info
    }

    private fun hasCoord(info: LocationInfo): Boolean =
        info.longitude != 0.0 || info.latitude != 0.0

    /**
     * 缓存是否可直接使用：有坐标 + 未过期 + 精度达标。
     */
    private fun isUsable(info: LocationInfo): Boolean {
        if (!hasCoord(info)) return false
        if (info.time.isEmpty()) return false
        if (!isFresh(info)) return false
        if (!LocationProviderPicker.meetsAccuracy(info.provider)) return false
        return true
    }

    /**
     * 是否未超过「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）。
     */
    private fun isFresh(info: LocationInfo): Boolean {
        val freshMs = maxOf(SettingUtils.locationMinInterval, MIN_FRESH_SEC * 1000L)
        return try {
            val ts = App.DateFormat.parse(info.time)?.time ?: return false
            System.currentTimeMillis() - ts < freshMs
        } catch (e: Exception) {
            false
        }
    }
}
