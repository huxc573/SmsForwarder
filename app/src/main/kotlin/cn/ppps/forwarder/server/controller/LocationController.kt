package cn.ppps.forwarder.server.controller

import android.annotation.SuppressLint
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.server.model.BaseRequest
import cn.ppps.forwarder.server.model.EmptyData
import cn.ppps.forwarder.utils.LocationOnceFetcher
import cn.ppps.forwarder.utils.Log
import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.PostMapping
import com.yanzhenjie.andserver.annotation.RequestBody
import com.yanzhenjie.andserver.annotation.RequestMapping
import com.yanzhenjie.andserver.annotation.RequestMethod
import com.yanzhenjie.andserver.annotation.RestController

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName")
@RestController
@RequestMapping(path = ["/location"])
class LocationController {

    private val TAG: String = LocationController::class.java.simpleName

    //远程找手机
    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/query")
    fun query(@RequestBody bean: BaseRequest<EmptyData>): LocationInfo {
        Log.d(TAG, bean.data.toString())
        //这是「远程找手机 / 刷新」，用户要的就是当下这一刻的位置：
        //interactive = true 表示用完整超时等待 GPS。
        //缓存是否可用由 LocationOnceFetcher 校验（有坐标 + 未过期 + 精度达标），
        //刚定位过一秒的连点刷新会直接复用，避免反复唤醒 GNSS。
        return LocationOnceFetcher.fetchIfNeeded(App.context, interactive = true)
    }

}
