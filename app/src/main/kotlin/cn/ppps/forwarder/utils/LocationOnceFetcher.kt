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
 * ## 取数流程（精确 / 不要求 完全相同）
 * ```
 * 0ms      同时注册 GPS + 网络
 * 0~gpsWait
 *   ├─ GPS 先到 → 用 GPS，全部注销，结束
 *   ├─ 网络先到 → 只暂存，继续等 GPS（不拿粗坐标冒充精确）
 *   └─ 什么都没到 → 继续等
 * gpsWait  到点
 *   ├─ 已有网络结果 → 用网络，全部注销，结束
 *   └─ 还没有结果   → 只注销 GPS（省电），网络继续等
 * 总超时   到点 → 全部注销；手上有结果就用，没有就返回旧缓存
 * ```
 * 「不要求」与「精确」唯一差别是 gpsWait 更短，见 [LocationProviderPicker.gpsWaitTimeoutMs]。
 * 「模糊」不注册 GPS，只用网络。
 *
 * ## 缓存
 * 只由设置里的**「位置更新的最小时间间隔」**决定新鲜度，与精度无关。
 * 超过该间隔就重新定位；只有**拿不到新数据**时才继续返回旧坐标。
 * 另外单独记录写入时刻来算年龄：网络 provider 首次回调常给一个较旧的点，
 * 若按定位时间算，缓存写进去就立刻「过期」，下次刷新又要重新定位。
 *
 * ## 两条预算
 *  - [clientWaitMs]：HTTP 请求能撑住的上限，超了先把手上的结果回给客户端，
 *    绝不拖到客户端自己超时报「网络连接超时」。
 *  - [serverBudgetMs]：定位本身愿意做多久。客户端先返回**不会取消定位**，
 *    后台跑完由监听器直接写缓存，下次刷新直接命中。
 *
 * ## 为什么不用 App.LocationClient
 * 它是单例，同一时刻只能持有一个请求，且回调走主线程 Looper：
 *  1. GPS 与网络无法并行，只能等完 GPS 再问网络；
 *  2. 调用方阻塞主线程时回调送不到，形成自锁。
 * 所以直接用系统 `LocationManager`，回调注册在自有后台线程上。
 */
object LocationOnceFetcher {

    private const val LOG_TAG = "LocationOnceFetcher"

    /** 留给 HTTP 层序列化与回包的余量 */
    private const val RESPONSE_MARGIN_MS = 1_500L

    /** 客户端等待下限，太短等于必定拿不到 */
    private const val MIN_CLIENT_WAIT_MS = 1_500L

    /** 定位回调专用后台线程 */
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val gate = Any()
    private var current: Session? = null

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
        val cachedAt = HttpServerUtils.apiLocationCacheAt

        //定位总开关关着，或者没开按需模式：保持上游行为，直接给缓存
        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return cached

        //没超过「最小时间间隔」→ 直接用缓存，不发起定位
        if (isUsable(cached)) return cached

        if (!canLocate(context)) {
            Log.w(LOG_TAG, "无法定位：权限或系统定位开关不满足")
            return cached
        }

        val session = joinOrCreate()
        ensureStarted(context, session)

        val wait = clientWaitMs()
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
            Log.w(LOG_TAG, "本轮未取到新结果，继续使用旧缓存 provider=${cached.provider}")
        } else {
            //客户端先返回，但定位不取消，后台跑完写缓存
            Log.i(LOG_TAG, "客户端等待 ${wait}ms 先返回，定位转入后台继续")
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

    private fun startAsync(context: Context, session: Session, budget: Long) {
        val h = handlerOrNew()
        h.post {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                finish(session)
                return@post
            }

            val coarseOnly = LocationProviderPicker.coarseOnly()
            val gpsWait = if (coarseOnly) 0L else LocationProviderPicker.gpsWaitTimeoutMs()
            val startedAt = SystemClock.elapsedRealtime()

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

            //先声明再赋值：onLocationChanged 内要引用这两个 listener 自身
            lateinit var gpsListener: LocationListener
            lateinit var networkListener: LocationListener

            gpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    Log.i(LOG_TAG, "GPS 命中，耗时 ${SystemClock.elapsedRealtime() - startedAt}ms")
                    session.best = location
                    publish(location)
                    stopAll(lm, session, gpsListener, networkListener, removeGps = true, removeNetwork = true)
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            networkListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    //已有 GPS 结果时不再被网络结果覆盖
                    val old = session.best
                    if (old != null && old.provider == LocationManager.GPS_PROVIDER) return

                    if (coarseOnly || gpsWait <= 0L) {
                        //模糊：网络就是目标结果
                        Log.i(LOG_TAG, "网络命中，耗时 ${SystemClock.elapsedRealtime() - startedAt}ms")
                        session.best = location
                        publish(location)
                        stopAll(lm, session, gpsListener, networkListener, removeGps = false, removeNetwork = true)
                        return
                    }

                    //精确 / 不要求：先记住，等 GPS 等待期结束再决定用不用
                    session.best = location
                    Log.i(LOG_TAG, "网络先到，暂存，继续等 GPS（最多 ${gpsWait}ms）")
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            val gpsReady = !coarseOnly && LocationProviderPicker.isReady(context, LocationManager.GPS_PROVIDER)
            val networkReady = LocationProviderPicker.isReady(context, LocationManager.NETWORK_PROVIDER)

            if (!gpsReady && !networkReady) {
                Log.w(LOG_TAG, "无可用 provider（GPS/网络均不可用）")
                finish(session)
                return@post
            }

            try {
                if (gpsReady) {
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, h.looper)
                }
                if (networkReady) {
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, networkListener, h.looper)
                }
                Log.i(LOG_TAG, "发起定位 gps=$gpsReady network=$networkReady gpsWait=${gpsWait}ms budget=${budget}ms")
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "请求定位失败：${e.message}")
                finish(session)
                return@post
            }

            //GPS 让位时刻：只注销 GPS，网络继续等
            if (gpsWait > 0L) {
                h.postDelayed({
                    if (session.stopped) return@postDelayed
                    try {
                        lm.removeUpdates(gpsListener)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "注销 GPS 失败：${e.message}")
                    }
                    val best = session.best
                    if (best != null) {
                        Log.i(LOG_TAG, "GPS 等待 ${gpsWait}ms 到点，使用网络结果，结束本轮")
                        publish(best)
                        stopAll(lm, session, gpsListener, networkListener, removeGps = false, removeNetwork = true)
                    } else {
                        Log.i(LOG_TAG, "GPS 等待 ${gpsWait}ms 到点仍无结果，继续等网络")
                    }
                }, gpsWait)
            }

            //总预算兜底：任何路径下都不让 GPS 一直挂着
            h.postDelayed({
                if (session.stopped) return@postDelayed
                val best = session.best
                if (best != null && !session.delivered) publish(best)
                Log.w(LOG_TAG, "到达总预算 ${budget}ms，注销全部监听")
                stopAll(lm, session, gpsListener, networkListener, removeGps = true, removeNetwork = true)
            }, budget)
        }
    }

    /** 注销监听并收尾。[removeGps]/[removeNetwork] 决定注销哪几路。 */
    @Suppress("DEPRECATION")
    private fun stopAll(
        lm: LocationManager,
        session: Session,
        gpsListener: LocationListener,
        networkListener: LocationListener,
        removeGps: Boolean,
        removeNetwork: Boolean
    ) {
        synchronized(session) {
            if (session.stopped) return
            session.stopped = true
        }
        try {
            if (removeGps) lm.removeUpdates(gpsListener)
            if (removeNetwork) lm.removeUpdates(networkListener)
            Log.i(LOG_TAG, "已注销监听 gps=$removeGps network=$removeNetwork")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "removeUpdates 失败：${e.message}")
        }
        finish(session)
    }

    /** 交付结果给等待方（只通知一次） */
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
     * 客户端等待预算：留足余量给 HTTP 回包，让客户端先拿到结果，
     * 而不是自己超时后报「网络连接超时」。
     */
    private fun clientWaitMs(): Long {
        val designed = serverBudgetMs()
        val clientMs = SettingUtils.requestTimeout.toLong() * 1000L - RESPONSE_MARGIN_MS
        return maxOf(minOf(designed, clientMs), MIN_CLIENT_WAIT_MS)
    }

    /** 定位预算：由「电耗 + 精度」决定，与客户端等待无关 */
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
     * 缓存是否可直接使用：有坐标 + 未超过「位置更新的最小时间间隔」。
     *
     * 只按设置里的最小间隔判断，不掺精度：精度只决定「重新定位时用谁」，
     * 不影响「已有坐标能不能先用」。否则室内（只有网络结果）会导致缓存永不命中，
     * 每次刷新都重新唤醒 GNSS，比不缓存更费电。
     */
    private fun isUsable(info: LocationInfo): Boolean {
        if (!hasCoord(info)) return false
        if (info.time.isEmpty()) return false
        return isFresh(info)
    }

    private fun isFresh(info: LocationInfo): Boolean {
        val interval = SettingUtils.locationMinInterval
        //间隔设为 0 表示不限制，即每次都取新的
        if (interval <= 0L) return false
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
        return age < interval
    }
}
