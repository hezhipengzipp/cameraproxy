## Why

当前共享拍照生成的 JPEG 在相册中会倾斜 90 度，说明静态拍照请求没有正确写入与设备方向匹配的 JPEG 方向信息。该问题会让拍照功能的结果不可直接使用，需要在服务端拍照管线中修复。

## What Changes

- 在服务端静态拍照请求中根据当前相机传感器方向、镜头朝向和设备显示旋转计算 JPEG 方向。
- 为 `TEMPLATE_STILL_CAPTURE` 设置 `CaptureRequest.JPEG_ORIENTATION`，让系统相册和常见图片查看器按正确方向显示照片。
- 保持现有 AIDL 拍照接口、输出描述符写入方式和并发请求队列不变。
- 增加竖屏和横屏拍照方向的验证任务。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared-photo-capture`: 拍照成功时保存的 JPEG 需要携带正确方向信息，避免在相册中倾斜 90 度。

## Impact

- 主要影响 `server/src/main/java/com/example/cameraproxy/server/CameraEngine.kt` 的静态拍照请求构建逻辑。
- 可能需要读取 `CameraCharacteristics.SENSOR_ORIENTATION`、`CameraCharacteristics.LENS_FACING` 和当前 display rotation。
- 不改变 `ICameraProxyService.capturePhoto`、`IPhotoCaptureCallback` 和客户端保存流程。
