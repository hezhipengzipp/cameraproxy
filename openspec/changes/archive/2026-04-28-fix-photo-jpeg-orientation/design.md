## Context

共享拍照当前由 `CameraEngine` 使用 `CameraDevice.TEMPLATE_STILL_CAPTURE` 输出 JPEG 到 `ImageReader`，再把 JPEG 字节写入客户端提供的 `ParcelFileDescriptor`。现有请求设置了自动曝光、自动对焦等控制项，但没有设置 `CaptureRequest.JPEG_ORIENTATION`，导致部分设备或查看器按传感器原始方向显示图片，表现为相册中照片倾斜 90 度。

客户端保存流程只负责创建 `MediaStore` 条目或文件描述符，不解析或重写 JPEG。因此方向修复应放在服务端拍照请求生成阶段，避免每个客户端重复处理。

## Goals / Non-Goals

**Goals:**

- 拍照时根据当前相机传感器方向、镜头朝向和设备显示旋转计算 JPEG 方向。
- 在每次静态拍照请求中设置 `CaptureRequest.JPEG_ORIENTATION`。
- 保持拍照 API、回调、队列和输出描述符写入方式不变。
- 覆盖竖屏和横屏拍照的手动验证。

**Non-Goals:**

- 不旋转 JPEG 像素数据，不引入图片解码/重编码流程。
- 不改变预览画面的方向、裁剪或镜像行为。
- 不新增客户端参数来传递方向。

## Decisions

### 在服务端设置 Camera2 JPEG 方向

服务端拥有 `CameraDevice` 和 `CameraCharacteristics` 上下文，且所有客户端拍照请求最终都会经过同一条 still capture 管线。在服务端统一设置 `CaptureRequest.JPEG_ORIENTATION`，可以让所有客户端获得一致结果，也不会扩大 AIDL 接口。

备选方案是在客户端收到成功回调后读取并重写 EXIF。该方案需要客户端重新打开输出文件、处理 `MediaStore` 权限和兼容性，并且会把方向修复责任分散到每个调用方，因此不采用。

### 缓存相机方向元数据，拍照时读取当前显示旋转

打开相机时读取并缓存 `CameraCharacteristics.SENSOR_ORIENTATION` 和 `CameraCharacteristics.LENS_FACING`。拍照时读取当前 display rotation，并按 Camera2 推荐公式计算 JPEG orientation：

- 后摄：`(sensorOrientation - deviceRotation + 360) % 360`
- 前摄：`(sensorOrientation + deviceRotation) % 360`

这样可以在同一次相机打开期间响应设备旋转变化，而不是只在 `openCamera` 时固定方向。

### 不修改 JPEG 字节写入流程

`ImageReader` 产出的 JPEG 字节仍按原有流程异步写入输出描述符。方向信息由 Camera HAL/Camera2 在 JPEG 元数据中表达，避免增加内存占用和写入耗时。

## Risks / Trade-offs

- 当前显示旋转读取可能在部分 Android 版本上 API 不同 → 使用兼容实现，优先从当前 display 获取 rotation，失败时回退到 `Surface.ROTATION_0`。
- 不同设备 HAL 对 `JPEG_ORIENTATION` 的支持可能存在差异 → 按 Camera2 标准设置请求字段，并通过真机竖屏/横屏验证确认目标设备行为。
- 如果客户端 Activity 未锁定方向，拍照按按下按钮时的当前显示旋转计算 → 这是预期行为，能匹配用户拍摄时设备姿态。
