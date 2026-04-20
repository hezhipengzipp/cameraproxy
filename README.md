# CameraProxy

一个独占 Camera 的 Service 进程 + AIDL 暴露订阅接口的参考实现。
对应文档里"Service + Binder/AIDL + SurfaceTexture GL fan-out"方案。

## 模块

```
CameraProxy/
├── aidl/      # 共享 AIDL + Parcelable（两端都依赖）
├── server/    # CameraHolderService：持相机，GL fan-out 到多个订阅者
└── client/    # 演示 app：bindService + SurfaceView 订阅
```

`:server` 的 Service 通过 `android:process=":camera"` 跑在独立进程里，
客户端通过显式 Intent `com.example.cameraproxy.ACTION_BIND` 跨包 bind。

## 关键实现点（对齐文档）

| 文档章节 | 代码位置 |
|----------|----------|
| 三、AIDL 接口设计 | `aidl/src/main/aidl/com/example/cameraproxy/*.aidl` |
| 四、Service 核心实现 | `CameraHolderService.kt` |
| 4.4 SurfaceTexture GL 中转 | `CameraEngine.kt` + `gl/*.kt`（OES 外部纹理 fan-out）|
| 六、Binder 死亡监听 | `CameraHolderService#subscribe` 里的 `linkToDeath` |
| 六、requestControl 模式 | `CameraHolderService` 的 `controlOwner` AtomicReference |

GL 路径：Camera -> 固定 SurfaceTexture -> OES 纹理 -> glDraw -> 每个订阅者
的 EGLWindowSurface -> `eglSwapBuffers` 直送客户端 SurfaceView。
新增/移除订阅者只在 GL 线程调整输出列表，CaptureSession 不重建，零黑屏。

## 跑起来

两个 App 分别装到同一台设备：

```bash
# 先生成 Gradle wrapper（项目没带 wrapper jar）
gradle wrapper --gradle-version 8.5

./gradlew :server:installDebug
./gradlew :client:installDebug
```

1. 打开 `CameraProxy Server`，授予 CAMERA 权限，点 "Start Camera Service"
   （会常驻前台，通知栏有摄像头图标）
2. 打开 `CameraProxy Client`，会自动 bind 并订阅
3. 多开几份 client（或复制一个新包名的 client）就能看到同一路画面被多进程共享

## 没做的事（留给读者）

- `setExposure` 只打了日志，真要生效需要让 `CameraEngine` 暴露一个
  `setRepeatingRequest` 的入口，把 `CaptureRequest.Builder` 上的 AE 参数改完再 apply
- 多订阅者分辨率差异很大时，可以按订阅者 Surface 尺寸各自 viewport
  （目前已经这么做了 —— `CameraEngine.drawFrame` 用 `eglQuerySurface` 拿尺寸后 `glViewport`）
- 降采样：某个订阅者只要 15fps 时，在 GL 线程按 id 跳帧即可
- 系统 Camera 被抢占（`onDisconnected`）的恢复策略 —— 目前只回调 `onCameraClosed`
- MediaCodec InputSurface 作为订阅 Surface（这步其实客户端完全无感，文档里的优化直接可用）
