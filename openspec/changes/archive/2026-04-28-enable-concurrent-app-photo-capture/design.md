## 背景

CameraProxy 当前由 `CameraHolderService`/`CameraEngine` 持有物理摄像头，并通过单个 Camera2 session 和 GL fan-out 将预览帧分发到多个客户端 `Surface`。这已经解决了多 APP 并发预览，但还没有 API 允许已订阅 APP 请求静态拍照。

静态拍照和预览的约束不同：JPEG 数据可能超过 Binder 事务大小限制，Camera2 静态拍照请求必须与当前活跃的 capture session 协调，并且某个 APP 的拍照请求不能导致其他订阅者的相机被关闭或重新配置。

## 目标 / 非目标

**目标：**

- 允许多个已订阅 APP 通过共享服务请求静态拍照。
- 保持物理摄像头只由服务端进程打开一次。
- 每个请求的结果只发送给发起请求的客户端。
- 避免通过 Binder 传输大块 JPEG `byte[]`。
- 处理拍照请求时保持预览订阅者继续活跃。
- 当请求方取消订阅、进程死亡、提供无效输出或相机未打开时，明确失败并清理资源。

**非目标：**

- 通过虚拟相机 HAL 提供完整 Camera2 兼容层。
- 为同一物理摄像头提供每个客户端独立的相机参数。
- 实现连拍、HDR、夜景等复杂拍照管线。
- 由服务端管理客户端侧存储权限；客户端负责拥有并传入目标文件描述符。

## 设计决策

### 新增专用拍照 AIDL 回调

新增 `IPhotoCaptureCallback`，包含 one-way 的成功和失败回调方法。在 `ICameraProxyService` 中新增 `capturePhoto(int subscriberId, in ParcelFileDescriptor output, IPhotoCaptureCallback callback): long`。

理由：拍照是异步操作，耗时可能超过同步 Binder 调用适合阻塞的时间。返回 request ID 可以让客户端稳定关联日志、重试和 UI 状态。

备选方案：复用 `ICameraCallback`。该方案会把预览生命周期事件和单次拍照事件混在一起，削弱请求关联能力。

### 使用客户端提供的 `ParcelFileDescriptor` 输出 JPEG

客户端打开目标文件，并把可写的 `ParcelFileDescriptor` 传给服务端。服务端写入编码后的 JPEG，并在成功或失败后关闭自己持有的描述符。

理由：JPEG 数据可能超过 Binder 事务限制。文件描述符让存储位置所有权留在客户端 APP，也避免服务端直接写入其他 APP 的媒体集合。

备选方案：在回调中返回 `byte[]`。这对小缩略图更简单，但对全分辨率图片不可靠，可能因 Binder 限制出现不可预测失败。

### 打开相机时配置静态拍照输出

`CameraEngine` 在打开相机时创建用于 JPEG 输出的 `ImageReader`，并把它的 `Surface` 与现有预览 `SurfaceTexture` surface 一起加入 Camera2 `CaptureSession`。预览继续使用指向预览 surface 的 repeating request；静态拍照请求指向 JPEG `ImageReader` surface。

理由：Camera2 session 不支持动态增加输出 surface。打开相机时预先准备静态拍照输出，可以避免每次拍照都重建 session，也能防止其他订阅者出现黑屏。

备选方案：从 GL 预览纹理截图。该方案可以避免 `ImageReader`，但照片会受限于预览分辨率，并丢失静态拍照的元数据和质量优势。

### 在 `CameraEngine` 内串行执行拍照请求

多个 APP 可以并发调用 `capturePhoto`，但 `CameraEngine` 在 camera thread 上按 FIFO 顺序处理静态拍照请求。每个排队请求保存 request ID、调用方 subscriber ID、输出描述符和回调桥接对象。当 `ImageReader` 收到 JPEG image 时，引擎将其写入对应请求的描述符并报告完成。

理由：FIFO 串行化可以避免多个待处理静态拍照请求与 `ImageReader` 产物之间匹配不清，也让共享相机状态更可预测。

备选方案：允许多个静态拍照请求同时飞行。该方案可在能力强的设备上提高吞吐，但会显著增加结果匹配和错误清理复杂度，首版共享拍照能力暂不需要。

### 在服务层校验请求方生命周期

`CameraHolderService` 只接受活跃订阅者的拍照请求。条件允许时，它会为拍照回调注册 Binder 死亡监听，并在 `unsubscribe` 或客户端死亡清理时取消该订阅者的待处理请求。

理由：拍照请求归属于发起它的订阅者。如果订阅者已经消失，继续写入其描述符或报告成功没有意义，还可能造成资源泄漏。

备选方案：允许任何已绑定客户端不订阅也能拍照。该方案会削弱生命周期归属，并绕过现有 subscriber record 模型。

## 风险 / 取舍

- 部分设备可能拒绝某个尺寸下的预览加 JPEG 输出组合 -> 从 `SCALER_STREAM_CONFIGURATION_MAP` 选择保守 JPEG 尺寸，清晰报告配置失败，并保持现有预览错误语义。
- FIFO 拍照会让高负载下的后续 APP 等待 -> 暴露 request ID 和单请求失败回调，并限制队列长度以避免内存和文件描述符压力。
- 静态拍照可能短暂影响预览曝光/对焦行为 -> 保持 repeating preview 活跃，并在必要时于每次拍照后恢复或重新下发预览 repeating request。
- 无效或已关闭输出描述符可能在请求接受后才失败 -> 通过 `IPhotoCaptureCallback` 报告失败，并关闭服务端资源。
- 相机权限属于服务端进程而不是客户端进程 -> 保留现有服务端权限检查，客户端通过代理拍照不要求持有 `CAMERA` 权限。
