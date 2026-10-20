package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.service.LocationService

/**
 * 按需定位：主动要一次最新坐标，并阻塞等待结果。
 *
 * 两种调用场景：
 *  1. 被控端远程查询 /location/query（客户端「远程找手机」/「刷新」）
 *     —— 必须 [force] = true。用户点刷新就是要当下这一刻的位置，
 *        手机丢了更不能拿旧坐标糊弄。
 *  2. 转发内容里出现 {{定位信息}} 等标签
 *     —— 按用户自己配置的「最小时间间隔」判定是否够新，避免一条短信唤醒一次 GNSS。
 *
 * 关于缓存：apiLocationCache 是上游原有设计，原版靠常驻定位不断刷新，
 * 所以缓存≈最近几十秒的位置。开启按需模式后缓存不再自动刷新，
 * 因此这里必须显式判断新鲜度并按需补一次定位，不能无脑读缓存。
 */
object LocationOnceFetcher {

    /** 等待一次定位结果的最长时间（秒），需覆盖「GPS 优先 + 网络回退」两段 */
    private const val DEFAULT_TIMEOUT_SEC = 22

    /** 新鲜度下限，避免用户把间隔设得过小时每条短信都打一次 GPS */
    private const val MIN_FRESH_SEC = 60

    fun fetchIfNeeded(
        context: Context,
        force: Boolean = false,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC
    ): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache

        if (!SettingUtils.enableLocation) return cached
        if (!SettingUtils.locationOnDemand) return cached

        //非强制场景（转发打标签）：缓存够新就直接用
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
        Log.w("LocationOnceFetcher", "等待定位超时（${timeoutSec}s），返回现有缓存")
        return HttpServerUtils.apiLocationCache
    }

    /**
     * 缓存是否够新：以用户配置的「最小时间间隔」为准（下限 [MIN_FRESH_SEC] 秒）。
     */
    private fun isFresh(info: LocationInfo): Boolean {
        if (info.longitude == 0.0 && info.latitude == 0.0) return false
        if (info.time.isEmpty()) return false
        val freshMs = maxOf(SettingUtils.locationMinInterval, MIN_FRESH_SEC * 1000L)
        return try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .parse(info.time)?.time ?: return false
            System.currentTimeMillis() - ts < freshMs
        } catch (e: Exception) {
            false
        }
    }
}
