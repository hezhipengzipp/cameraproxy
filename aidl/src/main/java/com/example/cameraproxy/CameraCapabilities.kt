package com.example.cameraproxy

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CameraSize(val width: Int, val height: Int) : Parcelable

@Parcelize
data class CameraCapabilities(
    val cameraId: String,
    val sensorOrientation: Int,
    val facing: Int,
    val supportedSizes: List<CameraSize>,
    val supportedFormats: IntArray
) : Parcelable
