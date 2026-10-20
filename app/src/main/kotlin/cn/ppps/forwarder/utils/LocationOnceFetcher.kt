package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.service.LocationService

/**
 * 按需定位：主动要一次最新坐标，并阻塞等待结果。
 *
 * 用于两种场景：
 *  1. 被控端远程查询 /location/query（客户端「远程找手机」/「刷新」→ 服务端转发到这里）
 *  2. 转发/通知内容里出现 {{定位信息}} 等标签时
 *
 * 只在「按需模式」（locationOnDemand = true）下才真正去唤醒定位；
 * 常驻模式下缓存在 LocationService 里一直是新鲜的，直接用即可。
 */
object LocationOnceFetcher {

    /** 等待一次定位结果的最长时间（秒） */
    private const val DEFAULT_TIMEOUT_SEC = 8

    fun fetchIfNeeded(context: Context, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache

        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return cached

        //缓存足够新鲜（10 分钟内）就直接用，避免每次转发都唤醒定位
        if (isFresh(cached)) return cached

        LocationService.requestOnce(context)

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val now = HttpServerUtils.apiLocationCache
            if (now.time != cached.time && (now.longitude != 0.0 || now.latitude != 0.0)) {
                return now
            }
            try {
                Thread.sleep(300L)
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
            System.currentTimeMillis() - ts < 10 * 60 * 1000L
        } catch (e: Exception) {
            false
        }
    }
}
