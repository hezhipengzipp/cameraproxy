package com.example.cameraproxy;

// 声明 CameraCapabilities 是一个 Parcelable 类型 —— AIDL 并不知道它的具体字段，
// 只是把它当成"能在 Binder 上传的数据"。真正的实现在同包下的 CameraCapabilities.kt，
// 由 @Parcelize 自动生成。其他 .aidl 文件 import 本类时才能通过编译。
parcelable CameraCapabilities;
