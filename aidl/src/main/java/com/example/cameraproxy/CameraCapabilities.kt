package com.example.cameraproxy

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 相机输出尺寸（宽 x 高，单位：像素）。
 *
 * 用 @Parcelize 让编译器自动实现 Parcelable，这样 AIDL 就能跨进程传输它。
 * 相机支持的分辨率通常是一组离散值（如 1920x1080、1280x720），
 * 客户端根据 supportedSizes 列表挑一个合适的尺寸再调 openCamera。
 */
@Parcelize
data class CameraSize(val width: Int, val height: Int) : Parcelable

/**
 * 相机能力描述 —— 封装一颗摄像头的静态属性，客户端据此决定怎么用。
 *
 * 字段说明：
 * - cameraId          ： Camera2 API 分配的相机 ID，一般 "0"=后置、"1"=前置。
 * - sensorOrientation ： 传感器相对设备自然方向的旋转角（0/90/180/270），
 *                       预览渲染时需要叠加这个角度防止画面歪。
 * - facing            ： 摄像头朝向。对应 CameraCharacteristics.LENS_FACING_*
 *                       （0=前置、1=后置、2=外接）。
 * - supportedSizes    ： 该相机支持输出的分辨率列表。
 * - supportedFormats  ： 支持的像素格式集合（ImageFormat.* 常量）。
 *
 * 本类通过 AIDL 跨进程从 Server 传给 Client，所以必须 Parcelable。
 */
@Parcelize
data class CameraCapabilities(
    val cameraId: String,
    val sensorOrientation: Int,
    val facing: Int,
    val supportedSizes: List<CameraSize>,
    val supportedFormats: IntArray
) : Parcelable