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
        //这是「远程找手机 / 刷新」，用户要的就是当下这一刻的位置，
        //必须强制重新定位（force = true），不能命中原有缓存。
        return LocationOnceFetcher.fetchIfNeeded(App.context, force = true)
    }

}
