package com.example.cameraproxy.server

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.example.cameraproxy.server.gl.EglCore
import com.example.cameraproxy.server.gl.OesTextureProgram
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 相机的唯一持有者（Single Source of Truth）。
 *
 * ## 总体设计：
 *   CameraDevice --> CameraCaptureSession --> 固定的一个 SurfaceTexture
 *       ↓ (每来一帧触发回调)
 *   GL 线程拿到这帧 OES 纹理，逐个 blit 到每个订阅者的 EGLWindowSurface。
 *
 * 关键优势：新增 / 移除订阅者**绝不**重建 CaptureSession —— 相机管线不动，
 *          没有黑屏闪烁，多客户端订阅只是 GL 侧多画几次。
 *
 * ## 线程模型：
 *   - cameraThread : 跑 Camera2 的异步回调（openCamera、CaptureSession）；
 *   - glThread     : 跑所有 GL / EGL 操作 —— 严格单线程，GL context 只绑这条线程；
 *   - 调用方线程   : openCamera/addSubscriber 等由 Service Binder 线程进来，
 *                   通过 runOnGlThreadBlocking 切到 GL 线程再操作。
 *
 * ## 生命周期：
 *   openCamera()  →  启线程 + 建 GL + 建 SurfaceTexture + 开相机 + 建 Session
 *   addSubscriber / removeSubscriber  →  动态增减 EGLWindowSurface
 *   closeCamera() →  关 Session + 关 Camera + 销毁 GL + 停线程
 */
class CameraEngine(private val appContext: Context, private val listener: Listener) {

    /**
     * 引擎事件回调，由 CameraHolderService 实现，再通过 AIDL 转发给所有订阅者。
     */
    interface Listener {
        /** 整条管线的第一帧已经画出来了（通常 open 完 200-500ms 内）。 */
        fun onFirstFrame()
        /** 引擎级错误，比如打开失败、Session 配置失败。 */
        fun onEngineError(code: Int, msg: String)
        /** 相机被 HAL 主动断开（其他相机 App 抢占、设备拔出等）。 */
        fun onCameraClosedByHal()
        /** 分辨率变化（当前版本暂未触发，预留给将来的"中途切换"能力）。 */
        fun onResolutionChanged(w: Int, h: Int)
    }

    /** Per-request still photo result bridge back to CameraHolderService. */
    interface PhotoCaptureCallback {
        fun onPhotoCaptureSucceeded(requestId: Long)
        fun onPhotoCaptureFailed(requestId: Long, msg: String)
    }

    // --- 只在 cameraThread 上访问的状态 ---
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegImageReader: ImageReader? = null
    private var jpegSurface: Surface? = null

    // --- 只在 photoWriterThread 上执行阻塞 JPEG 文件写入 ---
    private var photoWriterThread: HandlerThread? = null
    private var photoWriterHandler: Handler? = null

    // --- 只在 glThread 上访问的状态 ---
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglCore: EglCore? = null

    /**
     * 1x1 的 Pbuffer 垫底画板。为什么需要它？
     * SurfaceTexture.updateTexImage() 要求当前线程**有活的 GL context**。
     * 没有订阅者时我们也需要把相机帧吞掉（不然 BufferQueue 会堵），
     * 所以用这个 dummy surface 作"有地方可以 makeCurrent" 的兜底。
     */
    private var dummyPbuffer: EGLSurface? = null
    private var program: OesTextureProgram? = null
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    /** SurfaceTexture 包装成 Surface 后交给 Camera2 作为帧输出目标。 */
    private var cameraInputSurface: Surface? = null
    /** SurfaceTexture 提供的纹理变换矩阵（纠正翻转）。每帧刷新，传给 Shader。 */
    private val stMatrix = FloatArray(16)

    // --- 跨线程共享的状态（注意 @Volatile） ---
    @Volatile private var currentWidth = 0
    @Volatile private var currentHeight = 0
    @Volatile private var currentCameraId: String? = null
    @Volatile private var opened = false
    @Volatile private var firstFrameReported = false
    @Volatile private var cameraSensorOrientation = 0
    @Volatile private var cameraLensFacing = CameraCharacteristics.LENS_FACING_BACK

    /**
     * 订阅者表：id -> GL 绑定信息。
     * ConcurrentHashMap 让 Binder 线程（增删）和 GL 线程（遍历）可以同时访问。
     */
    private val subscribers = ConcurrentHashMap<Int, SubscriberGlBinding>()
    /** 订阅者 ID 生成器，从 1 开始自增。0 / -1 保留作为无效值。 */
    private val idGen = AtomicInteger(1)

    /** FIFO still-capture state, accessed only on cameraThread unless noted. */
    private val pendingPhotoRequests = ArrayDeque<PhotoRequest>()
    private var activePhotoRequest: PhotoRequest? = null

    /**
     * 每个订阅者对应的 GL 侧绑定。核心是 eglSurface —— 它关联了订阅者的
     * Surface，绘制后 swapBuffers 就会把画面送到对方进程。
     *
     * valid 标志用来在 drawFrame 中快速跳过已经失效的订阅者，
     * 避免"正在遍历时被 unsubscribe"的竞态。
     */
    private class SubscriberGlBinding(
        val id: Int,
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
        var width: Int = 0,
        var height: Int = 0,
        @Volatile var valid: Boolean = true
    )

    private class PhotoRequest(
        val requestId: Long,
        val subscriberId: Int,
        val output: ParcelFileDescriptor,
        val callback: PhotoCaptureCallback
    ) {
        private val finished = AtomicBoolean(false)

        fun isFinished(): Boolean = finished.get()

        fun succeed() {
            if (finished.compareAndSet(false, true)) {
                closeOutput()
                callback.onPhotoCaptureSucceeded(requestId)
            }
        }

        fun fail(msg: String) {
            if (finished.compareAndSet(false, true)) {
                closeOutput()
                callback.onPhotoCaptureFailed(requestId, msg)
            }
        }

        fun closeOutput() {
            try { output.close() } catch (_: Throwable) {}
        }
    }

    // region lifecycle

    /**
     * 幂等打开相机。
     * - 相同配置重复调直接返回 true；
     * - 不同配置会先 closeCameraInternal() 再开（会有短暂黑屏）。
     *
     * 加 @Synchronized 保证同时多个订阅者调用时只执行一次真正的 open。
     */
    @Synchronized
    fun openCamera(cameraId: String, width: Int, height: Int, format: Int): Boolean {
        if (opened && cameraId == currentCameraId && width == currentWidth && height == currentHeight) {
            return true
        }
        if (opened) {
            Log.w(TAG, "openCamera requested different config, tearing down first")
            closeCameraInternal()
        }

        startThreads()
        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        firstFrameReported = false

        // 在 GL 线程上建好 EGL + 纹理 + SurfaceTexture，阻塞到就绪。
        // 必须在 GL 线程做：createOesTextureId 涉及 GL 调用，GL 命令只能在
        // 绑定了 context 的那条线程执行。
        runOnGlThreadBlocking {
            eglCore = EglCore()
            dummyPbuffer = eglCore!!.createPbufferSurface(1, 1)
            eglCore!!.makeCurrent(dummyPbuffer!!)
            program = OesTextureProgram()
            oesTextureId = program!!.createOesTextureId()
            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                // 告诉 SurfaceTexture 期望的输出尺寸。Camera2 会配合这个尺寸协商 HAL。
                setDefaultBufferSize(width, height)
                // 每来一帧就把 drawFrame 排到 GL 线程执行。
                setOnFrameAvailableListener({ postFrameDraw() }, glHandler)
            }
            // 包成 Surface 传给 Camera2 当作帧输出目标
            cameraInputSurface = Surface(surfaceTexture)
        }

        // 异步打开相机 + 建 CaptureSession，内部用 CountDownLatch 等到完成。
        val ok = runCameraOpen(cameraId)
        opened = ok
        if (!ok) {
            closeCameraInternal()
        }
        return ok
    }

    @Synchronized
    fun closeCamera() {
        closeCameraInternal()
    }

    /**
     * 完整的关闭流程。顺序很关键：
     *   1. 先停相机（Session + Device），停止帧生产；
     *   2. 再清 GL（释放所有 EGLSurface、纹理、Program、EGL）；
     *   3. 最后停线程。
     * 顺序反了可能会在 GL 资源释放后还收到一帧，导致崩溃。
     */
    private fun closeCameraInternal() {
        opened = false

        runOnCameraThreadBlocking {
            failAllPhotoRequestsLocked("camera closed")
        }

        // 1. 关相机
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { jpegImageReader?.close() } catch (_: Throwable) {}
        captureSession = null
        cameraDevice = null
        jpegImageReader = null
        jpegSurface = null

        // 2. 拆 GL（必须在 GL 线程做）
        runOnGlThreadBlocking {
            // 先让所有订阅者失效，防止 drawFrame 并发画到已释放的 surface
            subscribers.values.forEach { bind ->
                bind.valid = false
                bind.eglSurface?.let { eglCore?.releaseSurface(it) }
                bind.eglSurface = null
            }
            cameraInputSurface?.release()
            surfaceTexture?.release()
            cameraInputSurface = null
            surfaceTexture = null
            if (oesTextureId != 0) {
                val tex = intArrayOf(oesTextureId)
                GLES20.glDeleteTextures(1, tex, 0)
                oesTextureId = 0
            }
            program?.release()
            program = null
            dummyPbuffer?.let { eglCore?.releaseSurface(it) }
            dummyPbuffer = null
            eglCore?.release()
            eglCore = null
        }

        // 3. 停 Handler 线程
        stopThreads()
    }

    // endregion

    // region subscriber api

    /**
     * 注册一个订阅者。
     *
     * @return 订阅者 ID（>= 1），失败返回 -1。
     *
     * 步骤：
     *   1. 生成唯一 ID 并放进 map（此时 eglSurface 还是 null）；
     *   2. 切到 GL 线程创建 EGLWindowSurface；
     *   3. 建不出来就从 map 里删掉 —— 保证 map 里的 binding 一定是"可绘制"的。
     */
    fun addSubscriber(surface: Surface): Int {
        if (!opened) return -1
        val id = idGen.getAndIncrement()
        val binding = SubscriberGlBinding(id, surface)
        subscribers[id] = binding

        runOnGlThreadBlocking {
            try {
                binding.eglSurface = eglCore!!.createWindowSurface(surface)
            } catch (t: Throwable) {
                Log.e(TAG, "createWindowSurface failed for id=$id", t)
                subscribers.remove(id)
            }
        }
        if (!subscribers.containsKey(id)) return -1
        return id
    }

    /**
     * 取消订阅。
     *
     * 步骤：
     *   1. 从 map 里移除 binding（此时 drawFrame 再也取不到它）；
     *   2. valid = false 让正在执行的 drawFrame 循环立即跳过；
     *   3. GL 线程上释放 EGLSurface。
     */
    fun removeSubscriber(id: Int) {
        val binding = subscribers.remove(id) ?: return
        binding.valid = false
        runOnGlThreadBlocking {
            binding.eglSurface?.let { eglCore?.releaseSurface(it) }
            binding.eglSurface = null
        }
    }

    fun subscriberCount(): Int = subscribers.size

    // endregion

    // region still photo api

    /**
     * Queue one JPEG capture. The queue is owned by cameraThread so Camera2
     * result matching stays FIFO and predictable across multiple apps.
     */
    fun capturePhoto(
        requestId: Long,
        subscriberId: Int,
        output: ParcelFileDescriptor,
        callback: PhotoCaptureCallback
    ): Boolean {
        return runOnCameraThreadBlocking {
            if (!opened || cameraDevice == null || captureSession == null || jpegSurface == null) {
                return@runOnCameraThreadBlocking false
            }
            if (pendingPhotoRequests.size >= MAX_PENDING_PHOTO_REQUESTS) {
                return@runOnCameraThreadBlocking false
            }
            pendingPhotoRequests.add(PhotoRequest(requestId, subscriberId, output, callback))
            processNextPhotoRequestLocked()
            true
        } ?: false
    }

    fun cancelPhotoRequest(requestId: Long, msg: String) {
        cameraHandler?.post {
            cancelPhotoRequestsLocked(msg) { it.requestId == requestId }
        }
    }

    fun cancelPhotoRequestsForSubscriber(subscriberId: Int, msg: String) {
        cameraHandler?.post {
            cancelPhotoRequestsLocked(msg) { it.subscriberId == subscriberId }
        }
    }

    private fun cancelPhotoRequestsLocked(msg: String, predicate: (PhotoRequest) -> Boolean) {
        val it = pendingPhotoRequests.iterator()
        while (it.hasNext()) {
            val request = it.next()
            if (predicate(request)) {
                it.remove()
                request.fail(msg)
            }
        }

        activePhotoRequest?.let { request ->
            if (predicate(request)) {
                request.fail(msg)
                // Keep it as active until Camera2 delivers or aborts this in-flight capture,
                // otherwise the next queued request could accidentally consume its JPEG.
            }
        }
    }

    private fun failAllPhotoRequestsLocked(msg: String) {
        while (!pendingPhotoRequests.isEmpty()) {
            pendingPhotoRequests.removeFirst().fail(msg)
        }
        activePhotoRequest?.fail(msg)
        activePhotoRequest = null
    }

    private fun processNextPhotoRequestLocked() {
        if (activePhotoRequest != null) return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val target = jpegSurface ?: return

        while (!pendingPhotoRequests.isEmpty()) {
            val request = pendingPhotoRequests.removeFirst()
            if (request.isFinished()) continue

            activePhotoRequest = request
            try {
                val capture = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(target)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, calculateJpegOrientation())
                }
                session.capture(capture.build(), object : CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        requestBuilder: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        failActivePhotoRequest(request, "still capture failed: ${failure.reason}")
                    }

                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        failActivePhotoRequest(request, "still capture aborted")
                    }
                }, cameraHandler)
            } catch (t: Throwable) {
                failActivePhotoRequest(request, t.message ?: "still capture failed")
            }
            return
        }
    }

    private fun handleJpegAvailable(reader: ImageReader) {
        val request = activePhotoRequest
        var bytes: ByteArray? = null
        val image = try { reader.acquireNextImage() } catch (t: Throwable) {
            if (request != null) failActivePhotoRequest(request, t.message ?: "failed to acquire JPEG image")
            return
        }
        try {
            if (image != null && request != null && !request.isFinished()) {
                val buffer = image.planes[0].buffer
                bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
            }
        } catch (t: Throwable) {
            if (request != null) failActivePhotoRequest(request, t.message ?: "failed to read JPEG image")
            return
        } finally {
            try { image?.close() } catch (_: Throwable) {}
        }

        if (request == null) return
        val jpeg = bytes
        if (request.isFinished()) {
            completeActivePhotoRequest(request)
            return
        }
        if (jpeg == null) {
            failActivePhotoRequest(request, "JPEG image was empty")
            return
        }

        val writer = photoWriterHandler
        if (writer == null) {
            failActivePhotoRequest(request, "photo writer thread is unavailable")
            return
        }
        writer.post {
            if (!request.isFinished()) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(request.output).use { out ->
                        out.write(jpeg)
                        out.flush()
                    }
                    request.succeed()
                } catch (t: IOException) {
                    request.fail(t.message ?: "failed to write JPEG")
                } catch (t: Throwable) {
                    request.fail(t.message ?: "failed to write JPEG")
                }
            }
            cameraHandler?.post {
                restorePreviewRepeatingLocked()
                completeActivePhotoRequest(request)
            }
        }
    }

    private fun failActivePhotoRequest(request: PhotoRequest, msg: String) {
        if (activePhotoRequest === request) {
            request.fail(msg)
            restorePreviewRepeatingLocked()
            completeActivePhotoRequest(request)
        }
    }

    private fun completeActivePhotoRequest(request: PhotoRequest) {
        if (activePhotoRequest === request) {
            activePhotoRequest = null
            processNextPhotoRequestLocked()
        }
    }

    private fun restorePreviewRepeatingLocked() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val preview = cameraInputSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(preview)
            session.setRepeatingRequest(req.build(), null, cameraHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "restore preview repeating failed", t)
        }
    }

    // endregion

    // region camera

    /**
     * 真正打开 Camera 并配置 CaptureSession。
     *
     * Camera2 的 API 都是异步的：调 openCamera 不会马上返回打开后的 device，
     * 而是通过 StateCallback 回调通知结果。这里用 CountDownLatch 把异步
     * API 封成同步调用，方便 openCamera() 外层判断成功/失败。
     *
     * 流程：
     *   openCamera → onOpened → createCaptureSession → onConfigured → setRepeatingRequest
     *   中间任何一步失败都会 countDown(latch) 让调用线程返回 false。
     */
    private fun runCameraOpen(cameraId: String): Boolean {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false

        try {
            updateCameraOrientationMetadata(manager, cameraId)
            val jpegSize = selectJpegSize(manager, cameraId, currentWidth, currentHeight)
            if (jpegSize == null) {
                listener.onEngineError(ERR_CONFIG_FAILED, "no supported JPEG output size")
                return false
            }
            jpegImageReader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.JPEG,
                MAX_JPEG_IMAGES
            ).apply {
                setOnImageAvailableListener({ reader -> handleJpegAvailable(reader) }, cameraHandler)
            }
            jpegSurface = jpegImageReader!!.surface

            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        // 预览输出进 SurfaceTexture，静态照片输出进 JPEG ImageReader。
                        // CaptureSession 不能动态追加输出，所以打开时一次性配置好。
                        device.createCaptureSession(
                            listOf(cameraInputSurface!!, jpegSurface!!),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    captureSession = s
                                    restorePreviewRepeatingLocked()
                                    success = true
                                    latch.countDown()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "capture session config failed")
                                    failAllPhotoRequestsLocked("capture session config failed")
                                    listener.onEngineError(ERR_CONFIG_FAILED, "capture session config failed")
                                    latch.countDown()
                                }
                            },
                            cameraHandler
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "createCaptureSession threw", t)
                        failAllPhotoRequestsLocked(t.message ?: "capture session config failed")
                        listener.onEngineError(ERR_CONFIG_FAILED, t.message ?: "unknown")
                        latch.countDown()
                    }
                }
                override fun onDisconnected(device: CameraDevice) {
                    // 一般是被其他进程（系统相机）抢占导致
                    opened = false
                    failAllPhotoRequestsLocked("camera disconnected")
                    device.close()
                    cameraDevice = null
                    listener.onCameraClosedByHal()
                    latch.countDown()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    opened = false
                    failAllPhotoRequestsLocked("camera device error $error")
                    device.close()
                    cameraDevice = null
                    listener.onEngineError(ERR_DEVICE + error, "camera device error $error")
                    latch.countDown()
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera threw", t)
            listener.onEngineError(ERR_OPEN_FAILED, t.message ?: "unknown")
            return false
        }
        // 超时兜底：3 秒还没配置成功就认为失败
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }

    private fun updateCameraOrientationMetadata(manager: CameraManager, cameraId: String) {
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            cameraSensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            cameraLensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
        } catch (t: Throwable) {
            cameraSensorOrientation = 0
            cameraLensFacing = CameraCharacteristics.LENS_FACING_BACK
            Log.w(TAG, "read camera orientation metadata failed; using defaults", t)
        }
    }

    private fun calculateJpegOrientation(): Int {
        val deviceRotation = currentDisplayRotationDegrees()
        return if (cameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (cameraSensorOrientation + deviceRotation) % 360
        } else {
            (cameraSensorOrientation - deviceRotation + 360) % 360
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int {
        val rotation = try {
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (t: Throwable) {
            Log.w(TAG, "read display rotation failed; using ROTATION_0", t)
            Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    // endregion

    // region GL fan-out

    /** SurfaceTexture 的帧回调在 glHandler 线程，这里再 post 一次 drawFrame —— 解耦。 */
    private fun postFrameDraw() {
        glHandler?.post { drawFrame() }
    }

    /**
     * 【核心】一帧到来 → 画给所有订阅者。
     *
     * 关键动作：
     *   1. makeCurrent 到 dummyPbuffer，让 updateTexImage 有 GL context；
     *   2. updateTexImage 把最新一帧从 BufferQueue 取出来，绑到 OES 纹理上；
     *      同时取变换矩阵 stMatrix；
     *   3. 遍历订阅者，逐个 makeCurrent 到它们的 EGLWindowSurface，画矩形，swapBuffers；
     *   4. 首帧完成时通知上层。
     *
     * 为什么要在循环里 makeCurrent 切来切去？
     *   每个订阅者的 EGLWindowSurface 尺寸可能不同（SurfaceView 大小），
     *   但共享同一个 GL context 和同一个 OES 纹理。切 draw 目标即可，
     *   不需要重新绑定纹理 —— 这就是"fan-out 零拷贝"的真正含义。
     */
    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val prog = program ?: return
        val core = eglCore ?: return

        // Step 1 & 2: 在 dummy 画板上刷新纹理（只需一次 GL context 即可）
        core.makeCurrent(dummyPbuffer!!)
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }

        // Step 3: 依次画给每个订阅者
        for (bind in subscribers.values) {
            if (!bind.valid) continue
            val dst = bind.eglSurface ?: continue
            try {
                core.makeCurrent(dst)

                // 首次绘制时查一下目标 Surface 的真实尺寸（Client 的 SurfaceView 布局决定）。
                // GLES 没有直接查询 surface 尺寸的 API，要通过 EGL 来读。
                if (bind.width == 0 || bind.height == 0) {
                    val wh = querySurfaceSize(dst)
                    bind.width = wh[0]; bind.height = wh[1]
                }
                GLES20.glViewport(0, 0, bind.width, bind.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                prog.draw(oesTextureId, stMatrix)
                // swapBuffers 才会把画面真正送到订阅者 Surface
                core.swapBuffers(dst)
            } catch (t: Throwable) {
                Log.w(TAG, "drawFrame failed for subscriber ${bind.id}; dropping", t)
                // 只标记失效、不从 map 删除 —— lifecycle 归 Service 管，
                // 这里强删会和 removeSubscriber 竞态。
                bind.valid = false
            }
        }

        // Step 4: 首帧事件只发一次
        if (!firstFrameReported) {
            firstFrameReported = true
            listener.onFirstFrame()
        }
    }

    /** 用 EGL 查询当前绑定 EGLSurface 的像素尺寸。 */
    private fun querySurfaceSize(s: EGLSurface): IntArray {
        val w = IntArray(1); val h = IntArray(1)
        android.opengl.EGL14.eglQuerySurface(
            android.opengl.EGL14.eglGetCurrentDisplay(), s, android.opengl.EGL14.EGL_WIDTH, w, 0
        )
        android.opengl.EGL14.eglQuerySurface(
            android.opengl.EGL14.eglGetCurrentDisplay(), s, android.opengl.EGL14.EGL_HEIGHT, h, 0
        )
        return intArrayOf(w[0], h[0])
    }

    // endregion

    // region threads

    /** 懒启动两条 HandlerThread：一条跑 Camera 回调，一条跑 GL 渲染。 */
    private fun startThreads() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("camera-proxy-cam").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (glThread == null) {
            glThread = HandlerThread("camera-proxy-gl").apply { start() }
            glHandler = Handler(glThread!!.looper)
        }
        if (photoWriterThread == null) {
            photoWriterThread = HandlerThread("camera-proxy-photo-writer").apply { start() }
            photoWriterHandler = Handler(photoWriterThread!!.looper)
        }
    }

    /** quitSafely 会等队列中的任务执行完再退出，避免任务丢失。 */
    private fun stopThreads() {
        cameraHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
        glHandler = null
        glThread?.quitSafely()
        glThread = null
        photoWriterHandler = null
        photoWriterThread?.quitSafely()
        photoWriterThread = null
    }

    /**
     * 把 block 同步跑到 GL 线程 —— 当前线程会等到 block 执行完再返回。
     *
     * 如果当前线程本来就是 GL 线程，直接执行避免死锁（CountDownLatch 在同一线程上
     * post + await 会永久阻塞）。
     */
    private fun runOnGlThreadBlocking(block: () -> Unit) {
        val h = glHandler ?: return
        if (Thread.currentThread() === glThread) { block(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        h.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await()
    }

    private fun <T> runOnCameraThreadBlocking(block: () -> T): T? {
        val h = cameraHandler ?: return null
        if (Thread.currentThread() === cameraThread) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        h.post {
            try { result = block() } finally { latch.countDown() }
        }
        latch.await()
        return result
    }

    // endregion

    private fun selectJpegSize(
        manager: CameraManager,
        cameraId: String,
        previewWidth: Int,
        previewHeight: Int
    ): Size? {
        val chars = try { manager.getCameraCharacteristics(cameraId) } catch (_: Throwable) { return null }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return null

        val previewRatio = previewWidth.toFloat() / previewHeight.coerceAtLeast(1)
        val ratioMatched = sizes.filter { size ->
            abs((size.width.toFloat() / size.height.coerceAtLeast(1)) - previewRatio) < 0.02f
        }
        val candidates = (ratioMatched.ifEmpty { sizes })
            .filter { it.width.toLong() * it.height <= MAX_JPEG_CAPTURE_AREA }
            .ifEmpty { ratioMatched.ifEmpty { sizes } }

        return candidates.maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * 查询相机静态能力（供 Client 通过 getCapabilities AIDL 调用）。
     * 这是 Camera2 的纯同步 API，不需要打开相机就能查。
     *
     * ImageFormat.PRIVATE：让 HAL 自选最高效的格式（通常是 YUV_420_888），
     * 用于 SurfaceTexture 输出最合适。
     */
    fun buildCapabilities(cameraId: String): com.example.cameraproxy.CameraCapabilities? {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try { manager.getCameraCharacteristics(cameraId) } catch (t: Throwable) { return null }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)
            ?.map { com.example.cameraproxy.CameraSize(it.width, it.height) }
            ?: emptyList()
        val formats = map.outputFormats ?: IntArray(0)
        return com.example.cameraproxy.CameraCapabilities(
            cameraId = cameraId,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK,
            supportedSizes = sizes,
            supportedFormats = formats
        )
    }

    companion object {
        private const val TAG = "CameraEngine"
        private const val MAX_PENDING_PHOTO_REQUESTS = 8
        private const val MAX_JPEG_IMAGES = 2
        private const val MAX_JPEG_CAPTURE_AREA = 1920L * 1080L
        /** CaptureSession 配置失败（比如 Surface 组合不被硬件支持）。 */
        const val ERR_CONFIG_FAILED = 100
        /** openCamera 抛异常（比如没权限）。 */
        const val ERR_OPEN_FAILED = 101
        /** CameraDevice.onError 回调 —— 实际错误码 = ERR_DEVICE + error。 */
        const val ERR_DEVICE = 200
    }
}
