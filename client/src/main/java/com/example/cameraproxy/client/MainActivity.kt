package com.example.cameraproxy.client

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.ImageFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService
import com.example.cameraproxy.IPhotoCaptureCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 订阅方的演示 Activity。
 *
 * 流程串联：
 *   1. onStart   →  bindService 连 Server 进程的 CameraHolderService；
 *   2. SurfaceView 创建好后，拿到 Surface；
 *   3. 两者都就绪时调 subscribe(surface, callback) 拿到订阅 ID；
 *   4. Server 直接把相机画面 GL 绘制到本 Activity 的 SurfaceView；
 *   5. onStop    →  主动 unsubscribe + unbindService。
 *
 * 关键认识：本 Activity **不持有相机**，也不做任何 Camera2 / GL 调用。
 *          它只提供一个 Surface 做"显示容器"，相机由 Server 进程独占。
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    /** Server 的 AIDL 代理，bindService 成功后被赋值。 */
    private var proxy: ICameraProxyService? = null

    /** 订阅 ID，-1 表示未订阅。Server 分配，>=1 有效。 */
    private var subscriberId: Int = -1

    /**
     * SurfaceView 比 Service 先就绪的临时兜底：
     * surfaceCreated 触发时如果 proxy 还没连上，就把 holder 存下来，
     * 等 onServiceConnected 时再尝试订阅。
     */
    private var pendingSurface: SurfaceHolder? = null

    /** 切主线程更新 UI 的 Handler。 */
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var captureButton: Button

    private val pendingPhotos = ConcurrentHashMap<Long, PendingPhoto>()
    private val earlyPhotoResults = ConcurrentHashMap<Long, PhotoResult>()

    private data class PendingPhoto(
        val displayName: String,
        val uri: Uri?,
        val file: File?
    )

    private data class PhotoOutput(val photo: PendingPhoto, val pfd: ParcelFileDescriptor)

    private data class PhotoResult(val success: Boolean, val msg: String?)

    /**
     * Service 连接监听器。
     * - onServiceConnected：拿到 Binder 后用 Stub.asInterface 包成代理，
     *                      内部是一个跨进程调用的 Proxy 对象。
     * - onServiceDisconnected：Server 进程异常死亡（非主动 unbind）时触发。
     */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            proxy = ICameraProxyService.Stub.asInterface(service)
            setStatus("bound to proxy")
            // SurfaceView 如果比 Service 先就绪，这里补上订阅动作
            pendingSurface?.let { trySubscribe(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            proxy = null
            subscriberId = -1
            setStatus("proxy disconnected")
        }
    }

    /**
     * Client 实现的回调接口。
     * 注意：Server 通过 Binder 跨进程调这些方法，**不一定**在主线程 —— 通常在
     *      Binder 线程池。所以访问 UI 必须切回主线程（见 setStatus）。
     */
    private val callback = object : ICameraCallback.Stub() {
        override fun onFrameStart() { setStatus("first frame") }
        override fun onError(code: Int, msg: String?) { setStatus("error $code: $msg") }
        override fun onCameraClosed() { setStatus("camera closed") }
        override fun onConfigChanged(width: Int, height: Int) {
            setStatus("config changed ${width}x${height}")
        }
    }

    private val photoCallback = object : IPhotoCaptureCallback.Stub() {
        override fun onPhotoCaptureSucceeded(requestId: Long) {
            handlePhotoResult(requestId, PhotoResult(success = true, msg = null))
        }

        override fun onPhotoCaptureFailed(requestId: Long, msg: String?) {
            handlePhotoResult(requestId, PhotoResult(success = false, msg = msg))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        status = findViewById(R.id.status)
        captureButton = findViewById(R.id.btn_capture_photo)
        captureButton.setOnClickListener { capturePhoto() }
        findViewById<Button>(R.id.btn_go_dvr).setOnClickListener {
            startActivity(Intent(this, DvrActivity::class.java))
        }
        // 注册 SurfaceView 的生命周期回调 —— 必须在 SurfaceView 创建后才能拿到 Surface
        findViewById<SurfaceView>(R.id.preview).holder.addCallback(this)
    }

    /**
     * 在 onStart 而不是 onCreate 里 bindService：
     * onStop 会 unbind，这样旋转 / 切后台时能正确释放相机资源。
     */
    override fun onStart() {
        super.onStart()
        // 隐式 Intent：通过 action + package 定位到 Server 包的 CameraHolderService。
        // Android 11+ 还要求 Client manifest 里声明 <queries> 才能看到目标包。
        val intent = Intent("com.example.cameraproxy.ACTION_BIND").apply {
            setPackage("com.example.cameraproxy.server")
        }
        // BIND_AUTO_CREATE：目标 Service 没起就顺便起起来
        val ok = bindService(intent, connection, BIND_AUTO_CREATE)
        setStatus(if (ok) "binding..." else "bindService returned false")
    }

    override fun onStop() {
        super.onStop()
        safeUnsubscribe()
        // unbindService 可能抛 IllegalArgumentException（没绑过就 unbind），吞掉
        try { unbindService(connection) } catch (_: Throwable) {}
        proxy = null
    }

    // --- SurfaceHolder.Callback ---

    /** SurfaceView 第一次就绪，尝试订阅（可能服务还没连上，trySubscribe 里有兜底）。 */
    override fun surfaceCreated(holder: SurfaceHolder) {
        pendingSurface = holder
        trySubscribe(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Server 会在首次绘制时 eglQuerySurface 拿到真实尺寸，本端不用关心
    }

    /** SurfaceView 销毁（切后台 / 旋转），立即退订避免画到无效 Surface 上。 */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pendingSurface = null
        safeUnsubscribe()
    }

    // --- 实现细节 ---

    /**
     * 尝试发起订阅。需要同时满足：Service 已连 + Surface 已就绪 + 未订阅。
     *
     * 顺序：挑后置相机 → openCamera → subscribe。
     * openCamera 是幂等的 —— 多个 Client 调也只开一次。
     */
    private fun trySubscribe(holder: SurfaceHolder) {
        val p = proxy ?: return
        if (subscriberId != -1) return
        try {
            val cameraId = pickBackCameraId(p) ?: run {
                setStatus("no camera id")
                return
            }
            // ImageFormat.PRIVATE = 让 HAL 自选最高效格式，适合 SurfaceTexture
            if (!p.openCamera(cameraId, 1280, 720, ImageFormat.PRIVATE)) {
                setStatus("openCamera failed"); return
            }
            subscriberId = p.subscribe(holder.surface, callback)
            setStatus(if (subscriberId > 0) "subscribed id=$subscriberId" else "subscribe failed")
            ui.post { captureButton.isEnabled = subscriberId > 0 }
        } catch (t: Throwable) {
            Log.e(TAG, "subscribe failed", t)
            setStatus("subscribe exception: ${t.message}")
        }
    }

    private fun capturePhoto() {
        val p = proxy ?: run { setStatus("proxy not connected"); return }
        val id = subscriberId
        if (id <= 0) {
            setStatus("not subscribed")
            return
        }

        val name = "PHOTO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val output = createPhotoOutput(name) ?: return
        try {
            val requestId = p.capturePhoto(id, output.pfd, photoCallback)
            try { output.pfd.close() } catch (_: Throwable) {}
            if (requestId <= 0L) {
                cleanupFailedPhoto(output.photo)
                setStatus("photo request rejected")
                return
            }
            pendingPhotos[requestId] = output.photo
            val earlyResult = earlyPhotoResults.remove(requestId)
            if (earlyResult != null) {
                handlePhotoResult(requestId, earlyResult)
            } else {
                setStatus("photo requested #$requestId")
            }
        } catch (t: Throwable) {
            try { output.pfd.close() } catch (_: Throwable) {}
            cleanupFailedPhoto(output.photo)
            Log.e(TAG, "capturePhoto failed", t)
            setStatus("photo exception: ${t.message}")
        }
    }

    private fun handlePhotoResult(requestId: Long, result: PhotoResult) {
        val photo = pendingPhotos.remove(requestId)
        if (photo == null) {
            earlyPhotoResults[requestId] = result
            return
        }
        if (result.success) {
            publishPhoto(photo)
            setStatus("photo saved: ${photo.displayName}")
        } else {
            cleanupFailedPhoto(photo)
            setStatus("photo failed $requestId: ${result.msg}")
        }
    }

    private fun createPhotoOutput(name: String): PhotoOutput? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: run { setStatus("MediaStore insert failed"); return null }
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: run {
                    contentResolver.delete(uri, null, null)
                    setStatus("open photo fd failed")
                    return null
                }
            PhotoOutput(PendingPhoto(name, uri, null), pfd)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: run { setStatus("pictures dir unavailable"); return null }
            dir.mkdirs()
            val file = File(dir, name)
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY
            )
            PhotoOutput(PendingPhoto(name, null, file), pfd)
        }
    }

    private fun publishPhoto(photo: PendingPhoto) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            photo.uri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
        } else {
            photo.file?.let { file ->
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }
        }
    }

    private fun cleanupFailedPhoto(photo: PendingPhoto) {
        photo.uri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        photo.file?.let { file -> runCatching { file.delete() } }
    }

    /**
     * 安全退订。
     * 关键点：先把 subscriberId 置 -1，再调 unsubscribe，避免异常重复调用。
     * unsubscribe 本身是幂等的（Server 端对未知 ID 直接忽略），但本地要自己守好状态。
     */
    private fun safeUnsubscribe() {
        val p = proxy
        val id = subscriberId
        subscriberId = -1
        ui.post { captureButton.isEnabled = false }
        if (p != null && id > 0) {
            try { p.unsubscribe(id) } catch (_: Throwable) {}
        }
    }

    /**
     * 粗暴选后置相机：先试 "0"，失败就试 "1"。
     * 严谨做法是遍历 getCameraIdList + 比对 LENS_FACING_BACK，
     * 但 demo 里这样就够了。
     */
    private fun pickBackCameraId(p: ICameraProxyService): String? {
        val tryIds = listOf("0", "1")
        for (id in tryIds) {
            try {
                if (p.getCapabilities(id) != null) return id
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 回调可能在 Binder 线程触发，用 Handler.post 切回主线程更新 TextView。 */
    private fun setStatus(msg: String) {
        ui.post { status.text = msg }
        Log.i(TAG, msg)
    }

    companion object { private const val TAG = "ProxyClient" }
}
