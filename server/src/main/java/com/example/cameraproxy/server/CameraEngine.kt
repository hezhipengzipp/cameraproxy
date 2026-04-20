package com.example.cameraproxy.server

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.cameraproxy.server.gl.EglCore
import com.example.cameraproxy.server.gl.OesTextureProgram
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for the camera.
 *
 * The design:
 *   CameraDevice -> CameraCaptureSession -> fixed SurfaceTexture (one)
 *   GL thread picks up each frame and blits it onto every subscriber's
 *   EGLWindowSurface. Adding / removing subscribers never re-creates the
 *   CaptureSession -- no black flash.
 */
class CameraEngine(private val appContext: Context, private val listener: Listener) {

    interface Listener {
        fun onFirstFrame()
        fun onEngineError(code: Int, msg: String)
        fun onCameraClosedByHal()
        fun onResolutionChanged(w: Int, h: Int)
    }

    // --- State owned by the camera handler thread ---
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // --- State owned by the GL render thread ---
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglCore: EglCore? = null
    private var dummyPbuffer: EGLSurface? = null
    private var program: OesTextureProgram? = null
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    // --- Shared state ---
    @Volatile private var currentWidth = 0
    @Volatile private var currentHeight = 0
    @Volatile private var currentCameraId: String? = null
    @Volatile private var opened = false
    @Volatile private var firstFrameReported = false

    private val subscribers = ConcurrentHashMap<Int, SubscriberGlBinding>()
    private val idGen = AtomicInteger(1)

    /** Binding per subscriber, lives entirely on the GL thread. */
    private class SubscriberGlBinding(
        val id: Int,
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
        var width: Int = 0,
        var height: Int = 0,
        @Volatile var valid: Boolean = true
    )

    // region lifecycle

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

        // Set up GL + SurfaceTexture on the GL thread and block until ready.
        runOnGlThreadBlocking {
            eglCore = EglCore()
            dummyPbuffer = eglCore!!.createPbufferSurface(1, 1)
            eglCore!!.makeCurrent(dummyPbuffer!!)
            program = OesTextureProgram()
            oesTextureId = program!!.createOesTextureId()
            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ postFrameDraw() }, glHandler)
            }
            cameraInputSurface = Surface(surfaceTexture)
        }

        // Open the camera (also async; wait for session configured).
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

    private fun closeCameraInternal() {
        opened = false
        // 1. Stop camera
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        captureSession = null
        cameraDevice = null

        // 2. Tear down GL
        runOnGlThreadBlocking {
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

        stopThreads()
    }

    // endregion

    // region subscriber api

    fun addSubscriber(surface: Surface): Int {
        if (!opened) return -1
        val id = idGen.getAndIncrement()
        val binding = SubscriberGlBinding(id, surface)
        subscribers[id] = binding

        // Create the EGLWindowSurface on the GL thread. Failure = drop subscriber.
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

    // region camera

    private fun runCameraOpen(cameraId: String): Boolean {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false

        try {
            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        device.createCaptureSession(
                            listOf(cameraInputSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    captureSession = s
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(cameraInputSurface!!)
                                    s.setRepeatingRequest(req.build(), null, cameraHandler)
                                    success = true
                                    latch.countDown()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "capture session config failed")
                                    listener.onEngineError(ERR_CONFIG_FAILED, "capture session config failed")
                                    latch.countDown()
                                }
                            },
                            cameraHandler
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "createCaptureSession threw", t)
                        listener.onEngineError(ERR_CONFIG_FAILED, t.message ?: "unknown")
                        latch.countDown()
                    }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    listener.onCameraClosedByHal()
                    latch.countDown()
                }
                override fun onError(device: CameraDevice, error: Int) {
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
        // wait up to 3s for session-configured
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }

    // endregion

    // region GL fan-out

    private fun postFrameDraw() {
        glHandler?.post { drawFrame() }
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val prog = program ?: return
        val core = eglCore ?: return

        // Always make current on pbuffer first so updateTexImage has a GL context.
        core.makeCurrent(dummyPbuffer!!)
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }

        for (bind in subscribers.values) {
            if (!bind.valid) continue
            val dst = bind.eglSurface ?: continue
            try {
                core.makeCurrent(dst)
                // Query the window size on first use (subscriber Surface may have
                // been sized by the client). GLES has no direct surface-size query,
                // so we use EGL to read width/height.
                if (bind.width == 0 || bind.height == 0) {
                    val wh = querySurfaceSize(dst)
                    bind.width = wh[0]; bind.height = wh[1]
                }
                GLES20.glViewport(0, 0, bind.width, bind.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                prog.draw(oesTextureId, stMatrix)
                core.swapBuffers(dst)
            } catch (t: Throwable) {
                Log.w(TAG, "drawFrame failed for subscriber ${bind.id}; dropping", t)
                bind.valid = false
                // Do NOT remove from the map here -- the service layer is the source
                // of truth for lifecycle. Just stop drawing.
            }
        }

        if (!firstFrameReported) {
            firstFrameReported = true
            listener.onFirstFrame()
        }
    }

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

    private fun startThreads() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("camera-proxy-cam").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (glThread == null) {
            glThread = HandlerThread("camera-proxy-gl").apply { start() }
            glHandler = Handler(glThread!!.looper)
        }
    }

    private fun stopThreads() {
        cameraHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
        glHandler = null
        glThread?.quitSafely()
        glThread = null
    }

    private fun runOnGlThreadBlocking(block: () -> Unit) {
        val h = glHandler ?: return
        if (Thread.currentThread() === glThread) { block(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        h.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await()
    }

    // endregion

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
        const val ERR_CONFIG_FAILED = 100
        const val ERR_OPEN_FAILED = 101
        const val ERR_DEVICE = 200
    }
}
