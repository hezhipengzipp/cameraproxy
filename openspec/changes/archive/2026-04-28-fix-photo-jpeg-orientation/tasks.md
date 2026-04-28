## 1. 相机方向元数据

- [x] 1.1 在 `CameraEngine` 打开相机时读取并缓存 `SENSOR_ORIENTATION` 和 `LENS_FACING`。
- [x] 1.2 增加显示旋转读取逻辑，将 `Surface.ROTATION_*` 转换为 0/90/180/270 度。
- [x] 1.3 增加 JPEG 方向计算函数，分别处理后摄和前摄方向补偿。

## 2. 静态拍照请求

- [x] 2.1 在 `TEMPLATE_STILL_CAPTURE` 请求中设置 `CaptureRequest.JPEG_ORIENTATION`。
- [x] 2.2 保持现有拍照队列、回调隔离和输出描述符写入流程不变。
- [x] 2.3 在方向元数据读取失败时使用安全回退值，并保留可诊断日志。

## 3. 验证

- [x] 3.1 运行 `./gradlew :server:compileDebugKotlin :client:compileDebugKotlin`，确认实现可编译。
- [x] 3.2 手动验证竖屏拍照后系统相册中照片按竖屏正向显示。
- [x] 3.3 手动验证横屏拍照后系统相册中照片按横屏正向显示。
- [x] 3.4 手动验证设备方向变化前后连续拍照时，每个请求仍收到自己的结果回调。
