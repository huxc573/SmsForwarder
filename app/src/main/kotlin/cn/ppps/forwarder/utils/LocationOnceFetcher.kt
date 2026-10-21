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
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 按需定位：需要坐标时取一次，阻塞调用方直到有结果或超时。
 *
 * ## 为什么不用 LocationClient
 * 上游的 `App.LocationClient` 是**单例**，同一时刻只能持有一次定位请求，
 * 且它的回调走主线程 Looper。这带来两个后果：
 *  1. GPS 和网络无法并行请求，只能「等完 GPS 再问网络」，室内要白等到超时才降级；
 *  2. 一旦调用方阻塞了主线程，回调永远送不到，`onStartCommand` 也派发不出去，形成自锁。
 * 所以这里直接用系统 `LocationManager`，注册在**自有后台线程**上：
 *  - GPS 与网络可以同时监听，谁先合规就用谁；室内通常 1~3 秒就能拿到网络结果；
 *  - 回调完全不依赖主线程，调用方无论是 HTTP 工作线程还是其它线程都不会死锁。
 *
 * ## 缓存可用性（三段，缺一不可）
 *  1. 有坐标 —— 经纬度非 0
 *  2. 未过期 —— 距今不超过「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）
 *  3. 精度达标 —— 精确 / 不要求模式下缓存必须是 GPS 结果
 *
 * ## 等待预算
 * 服务端阻塞时长必须短于客户端的请求超时（默认 10 秒），否则客户端先报
 * 「网络连接超时」，用户什么也拿不到。因此预算取
 * `min(设计的总超时, requestTimeout - 余量)`。
 */
object LocationOnceFetcher {

    private const val LOG_TAG = "LocationOnceFetcher"

    /** 新鲜度下限，避免间隔设置过小时每条短信都打一次 GPS */
    private const val MIN_FRESH_SEC = 60

    /** 非交互场景（转发打标签）的等待上限，不能把转发卡太久 */
    private const val TAG_TIMEOUT_MS = 8_000L

    /** 留给 HTTP 层序列化与回包的余量 */
    private const val RESPONSE_MARGIN_MS = 1_500L

    /** 预算下限，太短等于必定失败 */
    private const val MIN_BUDGET_MS = 3_000L

    /** 轮询间隔 */
    private const val POLL_MS = 120L

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
        var stopped = false
    }

    /**
     * 取一次位置。
     *
     * @param interactive true = 用户在客户端主动刷新（远程找手机），用完整预算；
     *                    false = 转发内容替换 `{{定位信息}}`，压短预算。
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

        val budget = budgetMs(context, interactive)
        val session = joinOrCreate()

        //只有抢占者负责真正发起定位
        if (session.best == null && !session.stopped) {
            synchronized(gate) {
                if (current === session) startAsync(context, session, budget)
            }
        }

        //等待结果。回调在其他线程，因此这里阻塞不会打断定位本身。
        try {
            session.done.await(budget + 1_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val got = session.best
        if (got != null) {
            val info = toInfo(got)
            HttpServerUtils.apiLocationCache = info
            Log.i(LOG_TAG, "定位完成 provider=${info.provider} 耗时在预算 ${budget}ms 内")
            return info
        }

        Log.w(LOG_TAG, "预算 ${budget}ms 内未取到位置，返回缓存 provider=${cached.provider}")
        return if (hasCoord(cached)) cached else cached
    }

    /**
     * 只发起、不等待。给服务端命令等场景用。
     */
    fun requestAsync(context: Context) {
        if (!SettingUtils.enableLocation) return
        if (!canLocate(context)) return
        val session = joinOrCreate()
        synchronized(gate) {
            if (current === session) startAsync(context, session, budgetMs(context, true))
        }
    }

    private fun joinOrCreate(): Session = synchronized(gate) {
        current ?: Session().also { current = it }
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
     * 在后台线程注册 GPS + 网络，谁先合规用谁。
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
            //愿意为 GPS 等多久：受预算约束，且不能吃掉全部预算
            val gpsWait = if (coarseOnly) 0L else
                minOf(LocationProviderPicker.gpsWaitTimeoutMs(), maxOf(budget - RESPONSE_MARGIN_MS * 2, 0L))
            val startedAt = SystemClock.elapsedRealtime()

            fun acceptable(location: Location): Boolean {
                if (coarseOnly) return true
                if (location.provider == LocationManager.GPS_PROVIDER) return true
                //网络结果只在 GPS 等待期结束后才算数，避免过早用低精度结果
                return SystemClock.elapsedRealtime() - startedAt >= gpsWait
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    //结果只进不退：已有 GPS 时不再被网络结果覆盖
                    val old = session.best
                    if (old != null && old.provider == LocationManager.GPS_PROVIDER &&
                        location.provider != LocationManager.GPS_PROVIDER
                    ) return
                    session.best = location
                    if (acceptable(location)) {
                        Log.i(LOG_TAG, "命中 provider=${location.provider}")
                        stopAll(lm, this, session)
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

    private fun finish(session: Session) {
        synchronized(gate) {
            if (current === session) current = null
        }
        session.done.countDown()
    }

    /**
     * 等待预算：永远留出余量给 HTTP 回包，绝不比客户端超时更久。
     */
    private fun budgetMs(context: Context, interactive: Boolean): Long {
        if (!interactive) return TAG_TIMEOUT_MS
        val clientMs = SettingUtils.requestTimeout.toLong() * 1000L
        val designed = LocationProviderPicker.totalTimeoutMs()
        return maxOf(minOf(designed, clientMs - RESPONSE_MARGIN_MS), MIN_BUDGET_MS)
    }

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
