## 背景与动机

CameraProxy 目前已经把 Camera2 的持有权集中在服务端，并能把预览帧分发给多个客户端 APP，但客户端还不能通过共享服务请求静态拍照。新增共享拍照能力后，多个 APP 可以在不各自打开物理摄像头、不争抢 Camera HAL 独占资源的情况下完成拍照。

## 变更内容

- 为已订阅客户端新增面向 AIDL 的静态拍照能力。
- 允许多个客户端 APP 在共享同一条已打开相机管线时发起拍照请求。
- 将拍照成功或失败结果回传给发起请求的客户端，不阻塞其他订阅者的预览分发。
- 继续由 `CameraHolderService` 和 `CameraEngine` 集中管理相机持有权、权限检查和生命周期。
- 不破坏现有预览订阅 API。

## 能力范围

### 新增能力
- `shared-photo-capture`: 定义已订阅 APP 如何并发请求静态拍照，并从共享相机服务接收按请求隔离的结果。

### 修改能力

## 影响范围

- `aidl/src/main/aidl/com/example/cameraproxy/` 中的 AIDL API。
- 如有需要，`aidl` 共享模块中的 Parcelable 请求/结果模型。
- `CameraHolderService.kt` 中的服务端请求协调逻辑。
- `CameraEngine.kt` 中的 Camera2 静态拍照和 JPEG/ImageReader 处理逻辑。
- 演示客户端中触发拍照和处理结果的 UI/流程。
- Android 存储或输出描述符处理，用于向客户端交付 JPEG 数据。
