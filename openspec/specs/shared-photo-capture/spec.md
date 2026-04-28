# shared-photo-capture Specification

## Purpose
定义共享相机服务的静态拍照能力，确保已订阅 APP 可以在不独占物理摄像头的情况下请求 JPEG 照片，并按请求隔离结果、资源生命周期和失败回调。

## Requirements
### Requirement: 已订阅 APP 可以请求静态拍照
系统 SHALL 允许活跃订阅者从共享相机服务请求静态照片，而不需要客户端 APP 自行打开物理摄像头。

#### Scenario: 活跃订阅者请求拍照
- **WHEN** 客户端持有有效 subscriber ID，并使用可写输出描述符和回调调用拍照 API
- **THEN** 服务接受该请求并返回正数 request ID

#### Scenario: 非订阅者请求拍照
- **WHEN** 已绑定客户端使用未知或已取消订阅的 subscriber ID 调用拍照 API
- **THEN** 服务拒绝该请求，且不启动 Camera2 静态拍照

### Requirement: 多 APP 并发请求按请求隔离
系统 SHALL 支持多个已订阅 APP 发起拍照请求，并且 SHALL 只把每个结果报告给该请求提供的回调。

#### Scenario: 两个订阅者并发请求拍照
- **WHEN** 两个活跃订阅者在第一个请求完成前都调用拍照 API
- **THEN** 两个请求获得不同的 request ID，且每个回调只收到自己的完成或失败事件

#### Scenario: 拍照请求按受控顺序完成
- **WHEN** 相机打开期间多个拍照请求被接受
- **THEN** 服务处理这些请求时不关闭相机，也不使其他订阅者的预览流失效

### Requirement: 通过输出描述符写入照片数据
系统 SHALL 将捕获到的 JPEG 数据写入调用方提供的可写输出描述符，而不是通过 Binder 返回完整图片数据。

#### Scenario: 拍照成功
- **WHEN** Camera2 为已接受请求产出 JPEG image，且输出描述符可写
- **THEN** 服务写入 JPEG 字节，关闭服务端持有的描述符，并报告该请求拍照完成

#### Scenario: 输出描述符写入失败
- **WHEN** 服务无法将 JPEG 数据写入提供的输出描述符
- **THEN** 服务关闭服务端持有的描述符，并报告该请求拍照失败

### Requirement: 拍照期间继续共享预览
系统 SHALL 在处理已接受拍照请求期间保持现有预览订阅者活跃。

#### Scenario: 其他 APP 正在预览时发起拍照
- **WHEN** 一个订阅者请求拍照，同时另一个订阅者正在接收预览帧
- **THEN** 服务不会仅因为该拍照请求而取消订阅、关闭或重新配置其他订阅者的预览 surface

### Requirement: 拍照生命周期跟随订阅者生命周期
系统 SHALL 在订阅者取消订阅或进程死亡时，使该订阅者拥有的待处理拍照请求失败或取消。

#### Scenario: 订阅者在拍照完成前断开
- **WHEN** 持有待处理拍照请求的订阅者在请求完成前取消订阅或其 Binder 死亡
- **THEN** 服务取消或失败该请求，关闭服务端资源，并且不发送成功回调

### Requirement: 明确报告拍照失败
系统 SHALL 通过拍照回调按请求报告失败，并携带稳定 request ID 和错误信息。

#### Scenario: 相机未打开
- **WHEN** 已订阅客户端在 camera engine 未打开时请求拍照
- **THEN** 服务用错误拒绝或失败该请求，而不是静默忽略

#### Scenario: Camera2 静态拍照失败
- **WHEN** Camera2 为已接受请求报告错误或没有产出 JPEG image
- **THEN** 服务报告该请求拍照失败，并保持剩余订阅者的共享相机生命周期一致
