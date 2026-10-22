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
 * 按需定位：需要坐标时取一次，GPS 与网络结果分槽缓存。
 *
 * ## 两个维度，互不干涉
 *  - **精度**决定「要不要 GPS」：精确 / 不要求 → 注册 GPS + 网络，GPS 拿不到才用网络；
 *    模糊 → 只注册网络。精确与不要求在取数流程上没有任何差别。
 *  - **电耗**决定「愿意为 GPS 等多久」：低 5s / 中 12s / 高 20s / 不要求 30s。
 *    不参与 provider 选择，也不与精度做交集（上游 bug 就是把两者取交集导致无 provider 可用）。
 *
 * ## 缓存分两槽
 * GPS 与网络是两种精度完全不同的结果，共用一个槽会互相覆盖：
 * 拿到 GPS 后又刷新、这轮 GPS 没出点，网络结果会把精确坐标永久冲掉。
 * 所以拆成 [HttpServerUtils.apiLocationGps] 与 [HttpServerUtils.apiLocationNet]，
 * 各自独立记录定位时刻，互不覆盖。
 *
 * 目标是 GPS 时，只要 gps 槽不达标就重新请求 GPS，即使 network 槽刚取的很新鲜。
 * 拿到网络结果只是兜底，不等于任务完成。
 *
 * ## 两条预算
 *  - [clientWaitMs]：HTTP 请求能撑住的上限，超了先把手上的结果回给客户端，
 *    避免客户端自己超时报「网络连接超时」。
 *  - 定位预算：`gpsWait + NETWORK_TAIL_MS`。客户端先返回**不取消**定位，
 *    后台跑完由监听器直接写槽位，下次刷新直接命中。
 *
 * ## 为什么不用 App.LocationClient
 * 它是单例、回调走主线程 Looper：GPS 与网络无法并行；调用方阻塞主线程时回调送不到，
 * 形成自锁。改用系统 LocationManager + 后台线程，两路各自独立 listener 可分别注销——
 * 这是「GPS 让位、网络继续等」的前提。
 */
object LocationOnceFetcher {

    private const val LOG_TAG = "LocationOnceFetcher"

    /** GPS 让位后，留给网络回包的时间 */
    private const val NETWORK_TAIL_MS = 8_000L

    /** 留给 HTTP 层序列化与回包的余量 */
    private const val RESPONSE_MARGIN_MS = 1_500L

    /** 客户端等待下限，再短等于必定拿不到 */
    private const val MIN_CLIENT_WAIT_MS = 1_500L

    /**
     * 「回放点」判定阈值。
     *
     * 注册 requestLocationUpdates 后，系统会立刻回放该 provider 上一次已知位置，
     * 可能是几小时前的。不判别就当成新结果，会出现「室内定不到位却写入旧坐标」，
     * 堵住整个最小间隔窗口。超过该阈值即丢弃，继续等真实结果。
     */
    private const val STALE_FIX_MS = 60_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val gate = Any()
    private var current: Session? = null

    private class Session {
        val done = CountDownLatch(1)

        @Volatile
        var gpsFix: Location? = null

        @Volatile
        var netFix: Location? = null

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
        //定位总开关关着，或者没开按需模式：保持上游行为，直接给缓存
        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return mirror()

        val wantGps = !LocationProviderPicker.coarseOnly()
        val cached = if (wantGps) HttpServerUtils.apiLocationGps else HttpServerUtils.apiLocationNet
        val cachedAt = if (wantGps) HttpServerUtils.apiLocationGpsAt else HttpServerUtils.apiLocationNetAt

        //目标槽有效 → 直接用，不发起定位
        if (isUsable(cached, cachedAt, wantGps)) {
            Log.i(LOG_TAG, "缓存有效（${if (wantGps) "gps" else "network"}，间隔=${SettingUtils.locationMinInterval}ms），直接返回")
            return cached
        }

        if (!canLocate(context)) {
            Log.w(LOG_TAG, "无法定位：权限或系统定位开关不满足，返回现有缓存")
            return mirror()
        }

        Log.i(LOG_TAG, "发起定位 wantGps=$wantGps 现有缓存=${cached.provider}@${cached.time}")

        //记下发起前的时刻，用来判断本轮到底有没有拿到新结果
        val gpsAtBefore = HttpServerUtils.apiLocationGpsAt
        val netAtBefore = HttpServerUtils.apiLocationNetAt

        val session = joinOrCreate()
        ensureStarted(context, session)

        val wait = clientWaitMs()
        val finished = try {
            session.done.await(wait, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }

        val gpsNow = HttpServerUtils.apiLocationGpsAt
        val netNow = HttpServerUtils.apiLocationNetAt
        val gpsGot = gpsNow > 0L && gpsNow != gpsAtBefore
        val netGot = netNow > 0L && netNow != netAtBefore

        if (wantGps) {
            //① 拿到新的 GPS → 直接给
            if (gpsGot) {
                Log.i(LOG_TAG, "返回 gps（等待 ${wait}ms，本轮${if (finished) "已结束" else "后台继续"}）")
                return HttpServerUtils.apiLocationGps
            }
            //② GPS 没拿到 → 用网络槽兜底（不重新请求网络）
            if (netGot || isUsable(HttpServerUtils.apiLocationNet, HttpServerUtils.apiLocationNetAt, false)) {
                Log.i(LOG_TAG, "GPS 未出结果，返回 network 兜底 provider=${HttpServerUtils.apiLocationNet.provider}")
                return HttpServerUtils.apiLocationNet
            }
            //③ 都没有 → 返回 gps 旧值
            Log.w(LOG_TAG, "本轮未取到任何新结果，返回旧缓存 provider=${cached.provider}")
            return cached
        }

        //模糊：只要网络
        if (netGot) {
            Log.i(LOG_TAG, "返回 network（等待 ${wait}ms）")
            return HttpServerUtils.apiLocationNet
        }
        if (finished) {
            Log.w(LOG_TAG, "本轮未取到新结果，返回旧缓存 provider=${cached.provider}")
        } else {
            Log.i(LOG_TAG, "客户端等待 ${wait}ms 先返回，定位转入后台继续")
        }
        return cached
    }

    /** 兼容旧调用方/常驻模式：返回最近一次可用的坐标（优先 GPS） */
    private fun mirror(): LocationInfo =
        if (hasCoord(HttpServerUtils.apiLocationGps)) HttpServerUtils.apiLocationGps
        else if (hasCoord(HttpServerUtils.apiLocationNet)) HttpServerUtils.apiLocationNet
        else HttpServerUtils.apiLocationCache

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

            /** 定位点距今多久；时间戳不可用时按 0 处理（视为新鲜） */
            fun ageOf(location: Location): Long {
                val t = location.time
                if (t <= 0L) return 0L
                return (System.currentTimeMillis() - t).coerceAtLeast(0L)
            }

            /** 系统回放的陈旧位置，丢弃 */
            fun isStale(location: Location): Boolean = ageOf(location) > STALE_FIX_MS

            /** 写进对应的槽位，并同步兼容字段 */
            fun publish(location: Location, isGps: Boolean) {
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
                if (isGps) {
                    HttpServerUtils.apiLocationGps = info
                    HttpServerUtils.apiLocationGpsAt = System.currentTimeMillis()
                } else {
                    HttpServerUtils.apiLocationNet = info
                    HttpServerUtils.apiLocationNetAt = System.currentTimeMillis()
                }
                HttpServerUtils.apiLocationCache = info
                HttpServerUtils.apiLocationCacheAt = System.currentTimeMillis()
                deliver(session)
            }

            //先声明再赋值：onLocationChanged 内要引用 listener 自身
            lateinit var gpsListener: LocationListener
            lateinit var netListener: LocationListener

            gpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    if (isStale(location)) {
                        Log.w(LOG_TAG, "GPS 回放点过旧（${ageOf(location)}ms），丢弃")
                        return
                    }
                    Log.i(LOG_TAG, "GPS 命中，耗时 ${SystemClock.elapsedRealtime() - startedAt}ms")
                    session.gpsFix = location
                    publish(location, isGps = true)
                    stopAll(lm, session, gpsListener, netListener, removeGps = true, removeNet = true)
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            netListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session.stopped) return
                    if (isStale(location)) {
                        Log.w(LOG_TAG, "网络回放点过旧（${ageOf(location)}ms），丢弃")
                        return
                    }
                    //已有 GPS 结果时不再被网络结果覆盖
                    if (session.gpsFix != null) return

                    session.netFix = location
                    //网络结果总是先落到自己的槽位，GPS 拿不到时可直接兜底
                    publish(location, isGps = false)

                    if (coarseOnly) {
                        Log.i(LOG_TAG, "网络命中（模糊模式），耗时 ${SystemClock.elapsedRealtime() - startedAt}ms")
                        stopAll(lm, session, gpsListener, netListener, removeGps = false, removeNet = true)
                    } else {
                        Log.i(LOG_TAG, "网络先到，已存 network 槽，继续等 GPS（最多 ${gpsWait}ms）")
                    }
                }

                @Deprecated("Android 旧版本兼容")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            val gpsReady = !coarseOnly && LocationProviderPicker.isReady(context, LocationManager.GPS_PROVIDER)
            val netReady = LocationProviderPicker.isReady(context, LocationManager.NETWORK_PROVIDER)

            if (!gpsReady && !netReady) {
                Log.w(LOG_TAG, "无可用 provider（GPS/网络均不可用）")
                finish(session)
                return@post
            }

            try {
                if (gpsReady) {
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, h.looper)
                }
                if (netReady) {
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, netListener, h.looper)
                }
                Log.i(LOG_TAG, "发起定位 gps=$gpsReady network=$netReady gpsWait=${gpsWait}ms budget=${budget}ms")
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
                        @Suppress("DEPRECATION")
                        lm.removeUpdates(gpsListener)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "注销 GPS 失败：${e.message}")
                    }
                    if (session.gpsFix != null) return@postDelayed
                    if (session.netFix != null) {
                        Log.i(LOG_TAG, "GPS 等待 ${gpsWait}ms 到点，使用 network 结果，结束本轮")
                        deliver(session)
                        stopAll(lm, session, gpsListener, netListener, removeGps = false, removeNet = true)
                    } else {
                        Log.i(LOG_TAG, "GPS 等待 ${gpsWait}ms 到点仍无结果，继续等网络")
                    }
                }, gpsWait)
            }

            //总预算兜底：任何路径都不让 GPS 一直挂着
            h.postDelayed({
                if (session.stopped) return@postDelayed
                Log.w(LOG_TAG, "到达总预算 ${budget}ms，注销全部监听")
                stopAll(lm, session, gpsListener, netListener, removeGps = true, removeNet = true)
            }, budget)
        }
    }

    /** 注销监听并收尾 */
    @Suppress("DEPRECATION")
    private fun stopAll(
        lm: LocationManager,
        session: Session,
        gpsListener: LocationListener,
        netListener: LocationListener,
        removeGps: Boolean,
        removeNet: Boolean
    ) {
        synchronized(session) {
            if (session.stopped) return
            session.stopped = true
        }
        try {
            if (removeGps) lm.removeUpdates(gpsListener)
            if (removeNet) lm.removeUpdates(netListener)
            Log.i(LOG_TAG, "已注销监听 gps=$removeGps network=$removeNet")
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

    /** 定位预算：gpsWait + 网络尾部 */
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
     * 槽位是否可直接使用：有坐标 + 距**定位时刻**未超过最小时间间隔。
     *
     * 不掺精度：精度只决定「取哪个槽、要不要重新请求 GPS」，
     * 由调用方通过 [wantGps] 选择槽位来体现。
     */
    private fun isUsable(info: LocationInfo, writtenAt: Long, wantGps: Boolean): Boolean {
        if (!hasCoord(info)) return false
        if (info.time.isEmpty()) return false
        if (wantGps && info.provider != LocationManager.GPS_PROVIDER) return false
        return isFresh(info, writtenAt)
    }

    private fun isFresh(info: LocationInfo, writtenAt: Long): Boolean {
        val interval = SettingUtils.locationMinInterval
        //间隔设为 0 表示不限制，即每次都取新的
        if (interval <= 0L) return false
        //按「这次定位发生的时刻」算年龄，而不是写入缓存的时刻
        val fixedAt = try {
            App.DateFormat.parse(info.time)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
        val age = if (fixedAt > 0L) {
            System.currentTimeMillis() - fixedAt
        } else {
            System.currentTimeMillis() - writtenAt
        }
        return age in 0 until interval
    }
}
