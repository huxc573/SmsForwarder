package cn.ppps.forwarder.utils

import android.content.Context
import android.location.Criteria
import android.location.LocationManager

/**
 * 定位策略：**精度决定用谁，电耗决定等多久**。
 *
 * 背景（上游 3.5.x 的缺陷）：
 *  LocationClient 内部用 LocationManager.getBestProvider(criteria, true) 选 provider，
 *  而 Android 12+ 上该方法恒返回 "fused"；Criteria 只影响这一次选择，之后就再没用了。
 *  fused 会自行决定是否拉起 GNSS，导致「模糊位置」「低电耗」两个设置实际上完全失效，
 *  GPS 会被持续点亮（实测占开机时长 87.5%）。
 *
 * 设置值对照（SettingsFragment）：
 *  位置精度：精确 = ACCURACY_FINE(1)，模糊 = ACCURACY_COARSE(2)，不要求 = NO_REQUIREMENT(0)
 *  电量消耗：低 = POWER_LOW(1)，中 = POWER_MEDIUM(2)，高 = POWER_HIGH(3)，不要求 = NO_REQUIREMENT(0)
 *
 * 职责划分（与原版 Criteria 交集那套的区别）：
 *  精度 → 用不用 GPS。精确 / 不要求 = 需要精确位置，优先 GPS；模糊 = 只要大致位置，直接用网络。
 *  电耗 → 愿意为 GPS 等多久。不再用来排除 GPS，避免出现「精确 + 低电耗 = 无 provider 可用」。
 */
object LocationProviderPicker {

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
     * 只查 [LocationManager.getProviders] 是不够的——GPS 开关关闭时 provider 仍然存在，
     * 对它注册监听不会报错但永远收不到回调，只会白挂着占用电量。
     */
    fun isReady(context: Context, provider: String): Boolean =
        hasProvider(context, provider) && isEnabled(context, provider)

    /**
     * 是否「只要大致位置」。只有显式选「模糊位置」才算；
     * 「精确」和「不要求」都按需要精确位置处理。
     */
    fun coarseOnly(): Boolean = SettingUtils.locationAccuracy == Criteria.ACCURACY_COARSE

    /**
     * 第一选择 provider。返回 null 表示不指定，交回系统（fused）。
     */
    fun primary(context: Context): String? {
        //模糊：只要大致位置，直接用网络，绝不点亮 GNSS
        if (coarseOnly()) return networkOrNull(context)

        //精确 / 不要求：优先 GPS，拿不到再退网络
        return gpsOrNull(context) ?: networkOrNull(context)
    }

    /**
     * 愿意为 GPS 等待多久（毫秒），由「电量消耗」设置决定。
     * 超时即回退网络定位，避免 GPS 长时间挂着耗电。
     *
     * 取值参考：GPS 冷启动通常 5~30 秒，热启动 1~5 秒。
     */
    fun gpsWaitTimeoutMs(): Long = when (SettingUtils.locationPowerRequirement) {
        Criteria.POWER_LOW -> 5_000L      //低：几乎不等，基本直接走网络兜底
        Criteria.POWER_MEDIUM -> 12_000L  //中：常规等待
        Criteria.POWER_HIGH -> 20_000L    //高：愿意多等
        else -> 30_000L                   //不要求：不多做限制，给足时间
    }

    /**
     * 单次定位的总超时（含 GPS 等待 + 网络回退）。
     */
    fun totalTimeoutMs(): Long = gpsWaitTimeoutMs() + 8_000L

    /**
     * 是否「必须精确位置」。只有显式选「精确」才要求 GPS。
     * 「不要求」代表不挑精度，网络结果同样可用。
     */
    fun needsGps(): Boolean = SettingUtils.locationAccuracy == Criteria.ACCURACY_FINE
}
