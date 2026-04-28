## 1. AIDL 契约

- [x] 1.1 新增 `IPhotoCaptureCallback.aidl`，提供 one-way 成功和失败回调，并在回调中携带 `requestId`。
- [x] 1.2 在 `ICameraProxyService.aidl` 中新增 `capturePhoto(int subscriberId, in ParcelFileDescriptor output, IPhotoCaptureCallback callback): long`。
- [x] 1.3 更新共享 AIDL 模块的 import 和构建输出，确保服务端与客户端都能编译 `ParcelFileDescriptor` 和新回调。

## 2. 服务端请求协调

- [x] 2.1 在 `CameraHolderService` 中新增拍照 request ID 生成和待处理请求跟踪。
- [x] 2.2 接受请求前校验 `subscriberId`、输出描述符、回调对象和服务端相机权限。
- [x] 2.3 在可行时为拍照回调绑定 Binder 死亡监听，并在所属订阅者取消订阅或死亡时取消/失败待处理拍照请求。
- [x] 2.4 将 engine 的拍照完成/失败结果桥接回该请求的 `IPhotoCaptureCallback`，并确保所有路径都会关闭服务端持有的描述符。

## 3. Camera Engine 静态拍照

- [x] 3.1 在 `openCamera` 期间创建 JPEG `ImageReader`，并将其 surface 与预览 surface 一起加入 Camera2 capture session。
- [x] 3.2 从 `SCALER_STREAM_CONFIGURATION_MAP` 选择受支持的 JPEG 尺寸，并在无法配置预览加 JPEG 输出时给出明确失败。
- [x] 3.3 在 camera thread 上实现 FIFO 静态拍照队列，队列项包含 request ID 和输出描述符。
- [x] 3.4 向 JPEG surface 下发 `TEMPLATE_STILL_CAPTURE` 请求，并将每个 `ImageReader` 结果匹配到当前活跃的排队请求。
- [x] 3.5 异步将 JPEG 字节写入输出描述符，关闭 image/descriptor 资源，并在需要时于拍照后恢复预览 repeating request。
- [x] 3.6 在相机关闭、HAL 断开或 engine 出错时取消或失败排队的拍照请求。

## 4. 演示客户端

- [x] 4.1 在演示客户端新增 UI 操作，用于创建目标文件描述符并调用 `capturePhoto`。
- [x] 4.2 在客户端实现 `IPhotoCaptureCallback`，根据成功/失败更新状态，并通过 MediaStore 或应用存储让保存的照片可见。
- [ ] 4.3 使用两个客户端入口或两个 APP 实例验证并发请求拍照时预览仍保持活跃。

## 5. 验证

- [x] 5.1 运行 `./gradlew :aidl:compileDebugKotlin :server:compileDebugKotlin :client:compileDebugKotlin`，或运行当前项目中最接近的编译任务。
- [x] 5.2 只有引入 Flutter 代码时才运行 `flutter analyze`；否则跳过并记录不适用。（未引入 Flutter 代码，N/A）
- [ ] 5.3 手动验证两个已订阅客户端可以并发请求拍照，且每个客户端只收到自己的结果回调。
- [ ] 5.4 手动验证待处理拍照期间取消订阅或进程死亡会关闭资源，并且不会错误报告成功。
