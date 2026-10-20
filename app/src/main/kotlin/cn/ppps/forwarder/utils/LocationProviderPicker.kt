package cn.ppps.forwarder.utils

import android.content.Context
import android.location.Criteria
import android.location.LocationManager

/**
 * 依据「位置精度」「电量消耗」两个设置，决定真正请求哪个 provider。
 *
 * 背景（上游 3.5.x 的缺陷）：
 *  LocationClient 内部用 LocationManager.getBestProvider(criteria, true) 选 provider，
 *  而 Android 12+ 上该方法恒返回 "fused"；Criteria 只影响这一次选择，之后就再没用了。
 *  fused 会自行决定是否拉起 GNSS，导致「模糊位置」「低电耗」两个设置实际上完全失效，
 *  GPS 会被持续点亮。
 *
 * 设置值对照（SettingsFragment）：
 *  位置精度：精确 = ACCURACY_FINE(1)，模糊 = ACCURACY_COARSE(2)，不要求 = NO_REQUIREMENT(0)
 *  电量消耗：低 = POWER_LOW(1)，中 = POWER_MEDIUM(2)，高 = POWER_HIGH(3)，不要求 = NO_REQUIREMENT(0)
 *
 * 策略：
 *  模糊 / 不要求精度            -> NETWORK_PROVIDER（不点亮 GNSS）
 *  精确 + 低电耗                 -> NETWORK_PROVIDER（不点亮 GNSS）
 *  精确 + 中/高/不要求电耗        -> GPS_PROVIDER 优先，拿不到再回退 NETWORK_PROVIDER
 *
 * 注意「不要求」语义：它表示不在乎电耗，因此可以使用高电耗的 GPS，
 * 而不是反过来被当作省电。
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
     * 第一选择 provider。返回 null 表示不指定，交回系统（fused）。
     */
    fun primary(context: Context): String? {
        val needFine = SettingUtils.locationAccuracy == Criteria.ACCURACY_FINE
        val lowPower = SettingUtils.locationPowerRequirement == Criteria.POWER_LOW

        //精确 + 不省电：优先 GPS，拿不到再退网络
        if (needFine && !lowPower) {
            return gpsOrNull(context) ?: networkOrNull(context)
        }

        //需要省电，或不需要精确：只用网络定位，绝不点亮 GNSS
        return networkOrNull(context)
    }

    /**
     * GPS 拿不到结果时的回退 provider。
     */
    fun fallback(context: Context): String? =
        networkOrNull(context) ?: gpsOrNull(context)
}
