package cn.ppps.forwarder.utils

import android.content.Context
import android.location.Criteria
import android.location.LocationManager

/**
 * 定位策略。
 *
 * ## 精度决定「要不要 GPS」
 *  - **模糊**：只要大致位置，只用网络，绝不点亮 GNSS。
 *  - **精确 / 不要求**：两者**走完全相同的流程** —— 先请求 GPS，同时并行挂网络，
 *    GPS 拿不到才用网络结果。二者没有任何流程差异。
 *
 * ## 电耗决定「愿意为 GPS 等多久」
 * 只是时长，与位置精度无关，不用来排除 provider。原版把精度与电耗取交集，
 * 「精确 + 低电耗」交集为空 → 无 provider 可用 → 退回常驻 GPS（实测占开机 87.5%）。
 *
 * 设置值对照（SettingsFragment）：
 *  位置精度：精确 = ACCURACY_FINE(1)，模糊 = ACCURACY_COARSE(2)，不要求 = NO_REQUIREMENT(0)
 *  电量消耗：低 = POWER_LOW(1)，中 = POWER_MEDIUM(2)，高 = POWER_HIGH(3)，不要求 = NO_REQUIREMENT(0)
 */
object LocationProviderPicker {

    /** GPS 等待结束后，留给网络回包的时间 */
    private const val NETWORK_TAIL_MS = 8_000L

    private fun lm(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun hasProvider(context: Context, provider: String): Boolean = try {
        lm(context)?.allProviders?.contains(provider) == true
    } catch (e: Exception) {
        false
    }

    private fun isEnabled(context: Context, provider: String): Boolean = try {
        lm(context)?.isProviderEnabled(provider) == true
    } catch (e: Exception) {
        false
    }

    private fun networkOrNull(context: Context): String? =
        if (hasProvider(context, LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null

    private fun gpsOrNull(context: Context): String? =
        if (isEnabled(context, LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null

    /**
     * provider 是否真的可用：系统里有它，并且没被用户关掉。
     *
     * 只查 [LocationManager.getProviders] 不够——GPS 开关关闭时 provider 仍然存在，
     * 对它注册监听不会报错但永远收不到回调，只会白挂着占用电量。
     */
    fun isReady(context: Context, provider: String): Boolean =
        hasProvider(context, provider) && isEnabled(context, provider)

    /** 是否「只要大致位置」。只有显式选「模糊位置」才算。 */
    fun coarseOnly(): Boolean = SettingUtils.locationAccuracy == Criteria.ACCURACY_COARSE

    /**
     * 缓存是否达到「目标 provider」。
     *
     * 精确 / 不要求都要 GPS 结果，只有「模糊」接受网络结果。
     * 缓存是网络结果时不算达标 —— 刷新时仍要重新请求 GPS。
     */
    fun cacheMeetsTarget(provider: String?): Boolean =
        if (coarseOnly()) true else provider == LocationManager.GPS_PROVIDER

    /**
     * 常驻定位（用户开了「持续定位」）时指定的 provider。
     * 返回 null 表示交给系统决定。
     */
    fun primary(context: Context): String? {
        if (coarseOnly()) return networkOrNull(context)
        return gpsOrNull(context) ?: networkOrNull(context)
    }

    /**
     * 愿意为 GPS 等待多久（毫秒）。
     *
     * 只看「电量消耗」这一个维度，与位置精度无关：
     *  低 5s / 中 12s / 高 20s / 不要求 30s
     *
     * 精度只决定「要不要请求 GPS」（见 [coarseOnly]），
     * 电耗只决定「等多久」——两者互不干涉。
     * 「模糊位置」不请求 GPS，本值不生效。
     */
    fun gpsWaitTimeoutMs(): Long = when (SettingUtils.locationPowerRequirement) {
        Criteria.POWER_LOW -> 5_000L
        Criteria.POWER_MEDIUM -> 12_000L
        Criteria.POWER_HIGH -> 20_000L
        else -> 30_000L
    }

    /**
     * 单次定位的总超时 = GPS 等待 + 网络兜底窗口。
     * 只是「还能等多久」，与客户端愿意等多久是两件事。
     */
    fun totalTimeoutMs(): Long =
        if (coarseOnly()) NETWORK_TAIL_MS else gpsWaitTimeoutMs() + NETWORK_TAIL_MS
}
