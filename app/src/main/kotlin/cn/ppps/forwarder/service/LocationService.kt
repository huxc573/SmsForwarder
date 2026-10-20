package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.utils.ACTION_LOCATION_ONESHOT
import cn.ppps.forwarder.utils.ACTION_RESTART
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.LocationProviderPicker
import cn.ppps.forwarder.utils.LocationUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.TASK_CONDITION_LEAVE_ADDRESS
import cn.ppps.forwarder.utils.TASK_CONDITION_TO_ADDRESS
import cn.ppps.forwarder.utils.TaskWorker
import cn.ppps.forwarder.utils.task.TaskUtils
import cn.ppps.forwarder.workers.LocationWorker
import com.king.location.LocationErrorCode
import com.king.location.OnExceptionListener
import com.king.location.OnLocationListener
import com.xuexiang.xaop.util.PermissionUtils
import java.util.Date

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName", "DEPRECATION")
class LocationService : Service() {

    private val TAG: String = LocationService::class.java.simpleName
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    /** 单次定位序号：每次请求 +1，用于作废上一轮遗留的定时回退 */
    private var oneShotToken = 0

    private val locationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                handleLocationStatusChanged()
            }
        }
    }

    companion object {
        var isRunning = false

        /** GPS 优先时，等 GPS 出结果的最长时间；超时则回退网络定位 */
        private const val GPS_FIRST_TIMEOUT_MS = 12_000L

        /** 单次定位总超时兜底，覆盖「GPS + 网络」两段 */
        private const val ONE_SHOT_TOTAL_TIMEOUT_MS = 25_000L

        /**
         * 按需定位：唤醒服务做一次单次定位，拿到结果后自动停止。
         * 常驻模式下不需要调用；按需模式下由 LocationOnceFetcher 调用。
         */
        fun requestOnce(context: Context) {
            try {
                val intent = Intent(context, LocationService::class.java).apply {
                    action = ACTION_LOCATION_ONESHOT
                }
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("LocationService", "requestOnce failed: ${e.message}")
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate: ")
        super.onCreate()

        if (!SettingUtils.enableLocation) return

        //注册广播接收器
        try {
            registerReceiver(locationStatusReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
        startService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            //系统重启服务（START_STICKY 场景），保持原有的自动恢复语义
            if (SettingUtils.enableLocation && !SettingUtils.locationOnDemand && !isRunning) {
                startService()
            }
            return START_STICKY
        }
        Log.i(TAG, "onStartCommand: ${intent.action}")

        when {
            intent.action == ACTION_LOCATION_ONESHOT -> oneShotFix()
            intent.action == ACTION_START && !isRunning -> startService()
            intent.action == ACTION_STOP && isRunning -> stopService()
            intent.action == ACTION_RESTART -> {
                if (SettingUtils.locationOnDemand) {
                    //切到按需模式：立刻停掉常驻定位，确保 GPS 不再被点亮
                    if (App.LocationClient.isStarted()) {
                        Log.i(TAG, "切换到按需模式，停止常驻定位")
                        App.LocationClient.stopLocation()
                    }
                } else {
                    restartLocation()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: ")
        super.onDestroy()

        //先注销广播接收器，避免 create 提前 return 时泄漏
        if (receiverRegistered) {
            try {
                unregisterReceiver(locationStatusReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver failed: ${e.message}")
            }
            receiverRegistered = false
        }

        if (!SettingUtils.enableLocation) return
        stopService()
    }

    private fun startService() {
        try {
            //清空缓存
            HttpServerUtils.apiLocationCache = LocationInfo()
            TaskUtils.locationInfoOld = LocationInfo()

            if (SettingUtils.enableLocation && PermissionUtils.isGranted(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) {

                //设置定位监听
                App.LocationClient.setOnLocationListener(object : OnLocationListener() {
                    override fun onLocationChanged(location: Location) {
                        //位置信息
                        Log.d(TAG, "onLocationChanged(location = ${location})")

                        val locationInfoNew = LocationInfo(
                            location.longitude, location.latitude, "", App.DateFormat.format(Date(location.time)), location.provider.toString()
                        )

                        //根据坐标经纬度获取位置地址信息（WGS-84坐标系）
                        val list = App.Geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (list?.isNotEmpty() == true) {
                            locationInfoNew.address = list[0].getAddressLine(0)
                        }

                        Log.d(TAG, "locationInfoNew = $locationInfoNew")
                        HttpServerUtils.apiLocationCache = locationInfoNew
                        TaskUtils.locationInfoNew = locationInfoNew

                        //触发自动任务
                        val locationInfoOld = TaskUtils.locationInfoOld
                        if (locationInfoOld.longitude != locationInfoNew.longitude || locationInfoOld.latitude != locationInfoNew.latitude || locationInfoOld.address != locationInfoNew.address) {
                            Log.d(TAG, "locationInfoOld = $locationInfoOld")

                            val gson = Gson()
                            val locationJsonOld = gson.toJson(locationInfoOld)
                            val locationJsonNew = gson.toJson(locationInfoNew)
                            enqueueLocationWorkerRequest(TASK_CONDITION_TO_ADDRESS, locationJsonOld, locationJsonNew)
                            enqueueLocationWorkerRequest(TASK_CONDITION_LEAVE_ADDRESS, locationJsonOld, locationJsonNew)

                            TaskUtils.locationInfoOld = locationInfoNew
                        }
                    }
                })

                //设置异常监听
                App.LocationClient.setOnExceptionListener(object : OnExceptionListener {
                    override fun onException(@LocationErrorCode errorCode: Int, e: Exception) {
                        Log.w(TAG, "onException(errorCode = ${errorCode}, e = ${e})")
                        if (SettingUtils.locationOnDemand) {
                            //按需模式下不重启常驻定位，避免把 GPS 又点亮
                            App.LocationClient.stopLocation()
                        } else {
                            restartLocation()
                        }
                    }
                })

                if (!SettingUtils.locationOnDemand) {
                    //常驻模式：立即开始持续定位
                    restartLocation()
                } else {
                    //按需模式：只挂监听，不注册任何定位，等 requestOnce() 来唤醒
                    Log.i(TAG, "startService: 按需模式，不注册定位")
                }
                isRunning = true
            } else if (!SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                Log.d(TAG, "stopLocation")
                App.LocationClient.stopLocation()
                isRunning = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "startService: ${e.message}")
            isRunning = false
        }
    }

    private fun stopService() {
        //清空缓存
        HttpServerUtils.apiLocationCache = LocationInfo()
        TaskUtils.locationInfoOld = LocationInfo()

        isRunning = try {
            //如果已经开始定位，则先停止定位。
            //上游此处写的是 SettingUtils.enableLocation && isStarted()，
            //关掉开关后 enableLocation 已是 false，导致监听永不注销 —— 这里修掉。
            if (App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
            stopForeground(true)
            stopSelf()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "stopService: ${e.message}")
            true
        }
    }

    /**
     * 做一次单次定位。
     *
     * 精确精度 + 非省电设置时按「GPS 优先，超时回退网络」策略：
     *   1. 先用 GPS 取点，最多等 [GPS_FIRST_TIMEOUT_MS]
     *   2. 拿不到再换 NETWORK_PROVIDER 继续等
     *   3. 总时长超过 [ONE_SHOT_TOTAL_TIMEOUT_MS] 强制收手，避免 GPS 一直挂着耗电
     */
    private fun oneShotFix() {
        try {
            if (!PermissionUtils.isGranted(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                Log.w(TAG, "oneShotFix: 无定位权限")
                return
            }
            if (!LocationUtils.isLocationEnabled(App.context) || !LocationUtils.hasLocationCapability(App.context)) {
                Log.w(TAG, "oneShotFix: 系统定位未开启")
                return
            }

            oneShotToken++
            val token = oneShotToken
            val baselineTime = HttpServerUtils.apiLocationCache.time

            fun hasNewFix(): Boolean = HttpServerUtils.apiLocationCache.time != baselineTime

            fun attempt(provider: String?) {
                if (token != oneShotToken) return
                try {
                    if (App.LocationClient.isStarted()) {
                        App.LocationClient.stopLocation()
                    }
                    App.LocationClient.setLocationOption(buildLocationOption(true, provider))
                    App.LocationClient.startLocation()
                    Log.i(TAG, "oneShotFix: 发起定位 provider=${provider ?: "系统默认"}")
                } catch (e: Exception) {
                    Log.e(TAG, "oneShotFix: provider=$provider 失败 ${e.message}")
                }
            }

            val primary = LocationProviderPicker.primary(App.context)
            attempt(primary)

            //GPS 优先：超时没结果就回退网络定位
            if (primary == LocationManager.GPS_PROVIDER) {
                handler.postDelayed({
                    if (token != oneShotToken || hasNewFix()) return@postDelayed
                    Log.w(TAG, "oneShotFix: GPS 未出结果，回退网络定位")
                    attempt(LocationProviderPicker.fallback(App.context))
                }, GPS_FIRST_TIMEOUT_MS)
            }

            //总超时兜底
            handler.postDelayed({
                if (token != oneShotToken) return@postDelayed
                if (App.LocationClient.isStarted()) {
                    Log.w(TAG, "oneShotFix: 总超时，停止定位")
                    App.LocationClient.stopLocation()
                }
            }, ONE_SHOT_TOTAL_TIMEOUT_MS)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "oneShotFix: ${e.message}")
        }
    }

    private fun restartLocation() {
        //如果已经开始定位，则先停止定位
        if (App.LocationClient.isStarted()) {
            App.LocationClient.stopLocation()
        }
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            App.LocationClient.setLocationOption(buildLocationOption(false))
            App.LocationClient.startLocation()
        } else {
            Log.w(TAG, "onException: GPS未开启")
        }
    }

    /**
     * 组装定位参数。
     * [once] = true 表示单次定位（按需模式），拿到结果自动停。
     * [providerOverride] 显式指定 provider；null 表示不指定，交回系统。
     */
    private fun buildLocationOption(
        once: Boolean,
        providerOverride: String? = LocationProviderPicker.primary(App.context)
    ) = App.LocationClient.getLocationOption()
        .setAccuracy(SettingUtils.locationAccuracy)//设置位置精度
        .setPowerRequirement(SettingUtils.locationPowerRequirement) //设置电量消耗
        .setMinTime(if (once) 0L else SettingUtils.locationMinInterval)//设置位置更新最小时间间隔
        .setMinDistance(if (once) 0 else SettingUtils.locationMinDistance)//设置位置更新最小距离
        .setOnceLocation(once)//单次定位后自动停止
        .setLastKnownLocation(false)//设置是否获取最后一次缓存的已知位置
        .apply {
            //关键：按精度/电耗显式指定 provider，绕开 getBestProvider 恒返回 fused 的问题
            providerOverride?.let { setProvider(it) }
        }

    private fun enqueueLocationWorkerRequest(
        conditionType: Int, locationJsonOld: String, locationJsonNew: String
    ) {
        val locationWorkerRequest = OneTimeWorkRequestBuilder<LocationWorker>().setInputData(
            workDataOf(
                TaskWorker.CONDITION_TYPE to conditionType, "locationJsonOld" to locationJsonOld, "locationJsonNew" to locationJsonNew
            )
        ).build()

        WorkManager.getInstance(applicationContext).enqueue(locationWorkerRequest)
    }

    private fun handleLocationStatusChanged() {
        //处理状态变化
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //已启用
            Log.d(TAG, "handleLocationStatusChanged: 已启用")
            if (SettingUtils.enableLocation && !SettingUtils.locationOnDemand && !App.LocationClient.isStarted()) {
                App.LocationClient.startLocation()
            }
        } else {
            //已停用
            Log.d(TAG, "handleLocationStatusChanged: 已停用")
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
        }
    }

}

