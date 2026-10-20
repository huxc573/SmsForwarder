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
 * 因此这里绕过 getBestProvider，显式指定 provider：
 *  - 位置精度 = 模糊（非 ACCURACY_FINE）→ 只用 NETWORK_PROVIDER，绝不使用 GNSS
 *  - 电量消耗 = 不要求 / 低电耗，且网络定位可用 → 用 NETWORK_PROVIDER，不拉起 GNSS
 *  - 其余（高精度 + 中/高电耗）→ 返回 null，交回系统自己挑 fused / GPS
 */
object LocationProviderPicker {

    fun pick(context: Context): String? {
        val lm = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?: return null

        val hasNetworkProvider = try {
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        //模糊定位：只用网络定位。即使网络当前不可用也不回退到 GNSS，
        //宁可这一次拿不到坐标，也不点亮 GPS。
        if (SettingUtils.locationAccuracy != Criteria.ACCURACY_FINE) {
            return if (hasNetworkProvider) LocationManager.NETWORK_PROVIDER else null
        }

        //高精度，但要求低电耗 / 不要求电耗：能拿到网络定位就不拉起 GNSS
        if (SettingUtils.locationPowerRequirement <= Criteria.POWER_LOW) {
            val networkEnabled = try {
                hasNetworkProvider && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                false
            }
            if (networkEnabled) return LocationManager.NETWORK_PROVIDER
        }

        return null
    }
}
