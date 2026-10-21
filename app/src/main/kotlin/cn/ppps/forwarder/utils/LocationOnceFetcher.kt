package cn.ppps.forwarder.utils

import android.content.Context
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.service.LocationService

/**
 * 按需定位：需要坐标时主动取一次，并阻塞等待结果。
 *
 * 缓存可用性判断（三段，缺一不可）：
 *   1. 有坐标      —— 经纬度非 0
 *   2. 未过期      —— 距今不超过用户设置的「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）
 *   3. 精度达标    —— 精确 / 不要求模式下，缓存必须是 GPS 结果；
 *                     上次如果是网络定位（几百米误差），即使刚生成也要重取
 *
 * 满足以上三条就用缓存，避免用户连点刷新时反复唤醒 GNSS；
 * 任何一条不满足就重新定位。
 *
 * 并发控制（single-flight）：
 *   同一时刻只允许一个在途定位请求。后到的调用者不重复 requestOnce()，
 *   而是等待第一个请求的结果——否则多个请求会互相 stop/start，
 *   把 GNSS 反复拉起来重新搜星，既费电又拖慢首次定位。
 *
 * 关于缓存：apiLocationCache 是上游原有设计（SharedPreference 持久化），
 * 原版靠常驻定位不断刷新，所以缓存≈最近几十秒的位置。
 * 开启按需模式后缓存不再自动刷新，因此必须显式校验上面三条，不能无脑读缓存。
 */
object LocationOnceFetcher {

    /** 新鲜度下限，避免用户把间隔设得过小时每条短信都打一次 GPS */
    private const val MIN_FRESH_SEC = 60

    /** 非交互场景（转发打标签）的最长等待，不能把短信转发卡太久 */
    private const val TAG_REPLACE_TIMEOUT_SEC = 8

    /** 轮询缓存的间隔 */
    private const val POLL_INTERVAL_MS = 200L

    /**
     * single-flight 闸门。
     * [inFlight] 为 null 表示当前没有请求在跑；否则保存抢占者的令牌。
     */
    private val gate = Any()
    private var inFlight: Any? = null

    /**
     * @param interactive true = 用户在客户端主动刷新（远程找手机），用完整超时；
     *                    false = 转发内容里替换 {{定位信息}}，超时压短。
     */
    fun fetchIfNeeded(context: Context, interactive: Boolean = true): LocationInfo {
        val cached = HttpServerUtils.apiLocationCache

        //定位总开关关着，或不是按需模式：保持上游行为，直接给缓存
        if (!SettingUtils.enableLocation || !SettingUtils.locationOnDemand) return cached

        //缓存可用就直接用（连点刷新、刚定位过一秒的情况）
        if (isUsable(cached)) return cached

        val timeoutSec = if (interactive) {
            (LocationProviderPicker.totalTimeoutMs() / 1000).toInt()
        } else {
            TAG_REPLACE_TIMEOUT_SEC
        }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L

        //抢占：只有第一个调用者真正发起定位，其余在旁边等同一个结果
        val mine = Any()
        val leader = synchronized(gate) {
            if (inFlight == null) {
                inFlight = mine
                true
            } else {
                false
            }
        }

        try {
            if (leader) {
                LocationService.requestOnce(context)
            } else {
                Log.i("LocationOnceFetcher", "已有定位在途，复用其结果，不重复请求")
            }

            while (System.currentTimeMillis() < deadline) {
                val now = HttpServerUtils.apiLocationCache
                //拿到新结果了：精度达标就直接返回；不达标继续等（GPS 失败后还有网络降级段）
                if (now.time != cached.time && hasCoord(now) &&
                    LocationProviderPicker.meetsAccuracy(now.provider)
                ) {
                    return now
                }
                //跟随者：leader 已收工，再等也不会产生新结果
                if (!leader && isIdle()) {
                    val last = HttpServerUtils.apiLocationCache
                    if (last.time != cached.time && hasCoord(last)) return last
                    break
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            if (leader) clearIfMine(mine)
        }

        //超时：有结果就降级返回（精度不够也总比空值强），没有就返回旧的
        val latest = HttpServerUtils.apiLocationCache
        Log.w(
            "LocationOnceFetcher",
            "等待定位结束（${timeoutSec}s），返回 provider=${latest.provider} time=${latest.time}"
        )
        return if (hasCoord(latest)) latest else cached
    }

    private fun isIdle(): Boolean = synchronized(gate) { inFlight == null }

    private fun clearIfMine(token: Any) {
        synchronized(gate) {
            if (inFlight === token) inFlight = null
        }
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
     * 是否未超过用户配置的「最小时间间隔」（下限 [MIN_FRESH_SEC] 秒）。
     */
    private fun isFresh(info: LocationInfo): Boolean {
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
