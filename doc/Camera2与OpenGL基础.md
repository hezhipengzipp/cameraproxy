# Camera2 与 OpenGL 基础

> 面向 CameraEngine 类的预习材料：先理清 Camera2 API 怎么用，再讲清楚 GL / EGL / OES 纹理的基础概念。
> 对照代码：`server/src/main/java/com/example/cameraproxy/server/CameraEngine.kt`

---

## 一、Camera2 API 基础

### 1. 核心类的心智模型

把相机想象成一条**工厂流水线**：

```
CameraManager  ←  工厂门卫，负责开门（打开某个相机）
    ↓
CameraDevice   ←  被打开的具体相机，代表一台硬件
    ↓
CaptureSession ←  已配置好输出管道的"生产线"
    ↓
CaptureRequest ←  一张"订单"（要拍什么参数）
    ↓
Surface(s)     ←  产品出货口（帧数据的落脚点）
```

### 2. 标准五步使用流程（对照 CameraEngine.kt）

#### 第 1 步：拿到 CameraManager

```kotlin
val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
// CameraEngine.kt:286
```

#### 第 2 步：查询能力（不需开相机就能查）

```kotlin
val chars = manager.getCameraCharacteristics(cameraId)
val sizes = chars.get(SCALER_STREAM_CONFIGURATION_MAP)
    .getOutputSizes(ImageFormat.PRIVATE)
val orientation = chars.get(SENSOR_ORIENTATION)   // 传感器朝向
val facing      = chars.get(LENS_FACING)          // 前置/后置
// CameraEngine.kt:441-450 buildCapabilities
```

#### 第 3 步：打开相机（异步！）

```kotlin
manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
    override fun onOpened(device: CameraDevice) { /* ← 打开成功 */ }
    override fun onDisconnected(device: CameraDevice) { /* 被抢占 */ }
    override fun onError(device: CameraDevice, error: Int) { /* 出错 */ }
}, handler)   // 回调会在 handler 所在线程触发
// CameraEngine.kt:291
```

> ⚠️ **关键**：`openCamera` 立即返回，**不代表相机已打开**，必须在 `onOpened` 里拿 device。
> 这就是为什么 CameraEngine 用 `CountDownLatch` 把异步封装成同步（CameraEngine.kt:283）。

#### 第 4 步：创建 CaptureSession

```kotlin
device.createCaptureSession(
    listOf(outputSurface),   // 告诉相机"帧数据往哪儿送"
    object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) { /* ← 就绪 */ }
        override fun onConfigureFailed(session: CameraCaptureSession) { /* 失败 */ }
    },
    handler
)
// CameraEngine.kt:297
```

**核心概念**：一个 Session 绑定一组**固定的** Surface。改 Surface 必须重建 Session（黑屏闪烁）。
所以本项目**所有订阅者共享同一个 `cameraInputSurface`（SurfaceTexture 包的）**，分发交给 GL 做 —— 这样就避开了重建。

#### 第 5 步：发请求

```kotlin
val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)  // 预览模板
req.addTarget(outputSurface)
req.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_ON)            // 可选参数
session.setRepeatingRequest(req.build(), null, handler)                // 持续下发
// CameraEngine.kt:302-304
```

两种下发方式：

- `setRepeatingRequest`：**预览**场景 —— 不停地按这个参数出帧
- `capture`：**拍照**场景 —— 只出一张

### 3. 关闭顺序（很重要，反了会崩）

```
session.close()  →  device.close()  →  Surface.release()
// CameraEngine.kt:204-207
```

---

## 二、OpenGL / EGL 基础

### 1. 为什么相机非要 + OpenGL？

相机出的是 **YUV 格式**（或更底层的 PRIVATE），而：

- 手机屏幕要的是 RGBA
- CPU 做 YUV → RGB 非常耗电耗时

GPU + OES 扩展 = **硬件直接把 YUV 当 RGBA 用**，全程不过 CPU。

### 2. EGL 是什么？

OpenGL 本身只管"怎么画"，不管"画到哪"。**EGL 就是 GL 和操作系统之间的胶水层**。

三个核心对象：

| 对象         | 比喻       | 说明                                 |
| ------------ | ---------- | ------------------------------------ |
| `EGLDisplay` | 整个画室   | 一般就是默认屏幕                     |
| `EGLContext` | 画家的状态 | 当前绑了哪个 Shader、哪个纹理等      |
| `EGLSurface` | 具体的画布 | 可以是窗口（显示）或离屏（中间计算） |

#### 关键规矩：eglMakeCurrent

**所有 GL 调用之前，必须先 `makeCurrent`**，告诉系统：「这条线程要用这个 Context 画到这个 Surface 上」。

```kotlin
eglCore.makeCurrent(eglSurface)     // ① 绑定到当前线程
GLES20.glClear(...)                 // ② GL 命令才生效
GLES20.glDrawArrays(...)
eglCore.swapBuffers(eglSurface)     // ③ 把"后台画好的"提交出去
```

本项目的 fan-out 秘诀：**循环 `makeCurrent` 切不同 EGLSurface，同一个纹理画多次**。

### 3. OpenGL 绘制的心智模型

```
   顶点数据          →    Vertex Shader      →    像素位置
(x,y 坐标 + 纹理坐标)      (算出屏幕坐标)

   纹理/颜色         →    Fragment Shader    →    最终像素颜色
(采样 OES 纹理)           (算出 RGBA)

                     →    FrameBuffer        →    EGLSurface
                          (GPU 内部画布)          (通过 swap 送出)
```

#### Shader 就是在 GPU 上跑的小程序

- **Vertex Shader**：处理每个**顶点**，确定画在屏幕哪里
- **Fragment Shader**：处理每个**像素**，确定是什么颜色

本项目只画一个铺满屏幕的矩形（4 个顶点），所以 Shader 极简。

### 4. SurfaceTexture —— Camera2 和 OpenGL 的桥梁

```
Camera HAL 写入 → [GraphicBuffer 队列] → SurfaceTexture.updateTexImage()
                                                 ↓
                                        绑到一个 OES 纹理 ID
                                                 ↓
                                        Shader 就能采样它了
```

**SurfaceTexture 的本质**：把 Camera 写出来的 buffer，直接"变成"一个 GL 纹理，零拷贝。

对应代码：

```kotlin
// CameraEngine.kt:128-134
oesTextureId = program.createOesTextureId()             // ① 先建一个空的 OES 纹理
surfaceTexture = SurfaceTexture(oesTextureId).apply {   // ② 让 SurfaceTexture 往它里面灌数据
    setDefaultBufferSize(width, height)
    setOnFrameAvailableListener({ postFrameDraw() }, glHandler)
}
cameraInputSurface = Surface(surfaceTexture)            // ③ 包装成 Surface 喂给 Camera2
```

### 5. 一帧的完整旅程（对照 drawFrame）

```
① Camera2 输出一帧 (硬件)
        ↓
② SurfaceTexture 内部的 BufferQueue 收到
        ↓
③ onFrameAvailable 触发 → postFrameDraw → drawFrame()
        ↓
④ eglMakeCurrent(dummyPbuffer)   ← 得先有 GL context
        ↓
⑤ st.updateTexImage()             ← 把新帧绑到 OES 纹理
        ↓
⑥ st.getTransformMatrix(stMatrix) ← 拿修正矩阵(因为纹理默认是翻转的)
        ↓
⑦ for 每个订阅者:
     eglMakeCurrent(订阅者的 EGLSurface)
     glViewport + glClear
     program.draw(oesTextureId, stMatrix)   ← Shader 采样 + 画矩形
     eglSwapBuffers                          ← 送到订阅者 Surface
```

### 6. OES 纹理 vs 普通 2D 纹理（一句话记忆）

| 类型                      | 数据源               | Shader 里的采样器                       |
| ------------------------- | -------------------- | --------------------------------------- |
| `GL_TEXTURE_2D`           | 你从内存上传         | `sampler2D`                             |
| `GL_TEXTURE_EXTERNAL_OES` | BufferQueue 直接映射 | `samplerExternalOES` + 扩展声明         |

---

## 三、学习路径建议

1. **先会用 Camera2 单 Surface 预览**（TextureView + CaptureSession）—— 跳过 GL
2. **再学 OpenGL 三角形**（画一个带颜色的三角形）—— 跳过相机
3. **最后合起来**：SurfaceTexture 接 Camera2 + OES 纹理 + 一个 Shader

---

## 附：本项目相关文件索引

| 文件                          | 主要职责                                              |
| ----------------------------- | ----------------------------------------------------- |
| `CameraEngine.kt`             | 相机 + GL 核心，管理线程和 fan-out                    |
| `gl/EglCore.kt`               | EGL 上下文 / Surface 创建与销毁                       |
| `gl/OesTextureProgram.kt`     | OES 纹理 Shader 程序和全屏四边形绘制                  |
| `CameraHolderService.kt`      | AIDL Binder 层，管理订阅者档案                        |
