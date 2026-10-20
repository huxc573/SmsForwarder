package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.service.LocationService

/**
 * 按需定位：主动要一次最新坐标，并阻塞等待结果。
 *
 * 两种调用场景：
 *  1. 被控端远程查询 /location/query（客户端「远程找手机」/「刷新」）—— 必须 [force] = true，
 *     用户点刷新就是要当下这一刻的位置，不能拿缓存糊弄。
 *  2. 转发/通知内容里出现 {{定位信息}} 等标签 —— 走缓存即可，避免每条短信都唤醒 GNSS。
 *
 * 只在「按需模式」（locationOnDemand = true）下才真正去唤醒定位；
 * 常驻模式下缓存在 LocationService 里一直是新鲜的，直接用即可。
 */
object LocationOnceFetcher {

    /** 等待一次定位结果的最长时间（秒）。需覆盖 GPS 优先 + 网络回退两段耗时。 */
    private const val DEFAULT_TIMEOUT_SEC = 20

    /** 缓存新鲜期：非强制模式下，这么久以内的坐标直接复用 */
    private const val CACHE_FRESH_MS = 10 * 60 * 1000L

    fun fetchIfNeeded(
        context: Context,
        force: Boolean = false,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC
    ): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache

        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return cached

        //非强制（转发打标签场景）：缓存够新就直接用
        if (!force && isFresh(cached)) return cached

        LocationService.requestOnce(context)

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val now = HttpServerUtils.apiLocationCache
            if (now.time != cached.time && (now.longitude != 0.0 || now.latitude != 0.0)) {
                return now
            }
            try {
                Thread.sleep(200L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return HttpServerUtils.apiLocationCache
    }

    private fun isFresh(info: LocationInfo): Boolean {
        if (info.longitude == 0.0 && info.latitude == 0.0) return false
        return try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .parse(info.time)?.time ?: return false
            System.currentTimeMillis() - ts < CACHE_FRESH_MS
        } catch (e: Exception) {
            false
        }
    }
}
