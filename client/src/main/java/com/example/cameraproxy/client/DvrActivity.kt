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
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DVR 录制界面。
 *
 * 布局说明：
 *   - SurfaceView 全屏显示实时预览（独立订阅，与录制互不影响）
 *   - 顶部显示状态和录制计时
 *   - 底部"开始录制 / 停止录制"按钮
 *
 * 订阅方式：
 *   - 预览：SurfaceView.surface  → 直接显示到屏幕
 *   - 录制：MediaCodec.InputSurface → H.264 硬件编码 → .mp4
 *   两路订阅同时存在，GL 线程会并行渲染到两个 Surface，互不干扰。
 *
 * 录制完成后调用 MediaScannerConnection.scanFile 通知媒体库，文件即刻出现在相册。
 */
class DvrActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var proxy: ICameraProxyService? = null
    private var previewSubscriberId = -1
    private var pendingSurface: SurfaceHolder? = null

    private var dvr: DvrSubscriberExample? = null
    @Volatile private var isRecording = false
    private var recordingStartMs = 0L

    // Android 10+ MediaStore 路径
    private var currentMediaUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    // Android 9 及以下文件路径
    private var currentOutputPath: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var btnRecord: Button

    /** 每 500ms 刷新一次计时器 */
    private val timerTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val sec = (System.currentTimeMillis() - recordingStartMs) / 1000
            timerText.text = String.format("%02d:%02d", sec / 60, sec % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            proxy = ICameraProxyService.Stub.asInterface(binder)
            setStatus("已连接服务")
            pendingSurface?.let { trySubscribePreview(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            proxy = null
            previewSubscriberId = -1
            setStatus("服务断开")
        }
    }

    /** 预览订阅的回调 */
    private val previewCallback = object : ICameraCallback.Stub() {
        override fun onFrameStart() = setStatus("预览中")
        override fun onError(code: Int, msg: String?) = setStatus("错误 $code: $msg")
        override fun onCameraClosed() = setStatus("相机已关闭")
        override fun onConfigChanged(w: Int, h: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dvr)

        statusText = findViewById(R.id.status)
        timerText  = findViewById(R.id.timer)
        btnRecord  = findViewById(R.id.btn_record)

        findViewById<SurfaceView>(R.id.preview).holder.addCallback(this)

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent("com.example.cameraproxy.ACTION_BIND").apply {
            setPackage("com.example.cameraproxy.server")
        }
        val ok = bindService(intent, connection, BIND_AUTO_CREATE)
        if (!ok) setStatus("bindService 失败，Server 未启动？")
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) stopRecording()
        safeUnsubscribePreview()
        try { unbindService(connection) } catch (_: Throwable) {}
        proxy = null
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        pendingSurface = holder
        trySubscribePreview(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pendingSurface = null
        safeUnsubscribePreview()
        ui.post { btnRecord.isEnabled = false }
    }

    // --- 预览订阅 ---

    private fun trySubscribePreview(holder: SurfaceHolder) {
        val p = proxy ?: return
        if (previewSubscriberId != -1) return
        try {
            val cameraId = pickBackCameraId(p) ?: run { setStatus("找不到摄像头"); return }
            if (!p.openCamera(cameraId, WIDTH, HEIGHT, ImageFormat.PRIVATE)) {
                setStatus("openCamera 失败"); return
            }
            previewSubscriberId = p.subscribe(holder.surface, previewCallback)
            if (previewSubscriberId > 0) {
                // subscribe 成功即可录制，不等 onFrameStart——
                // onFrameStart 是引擎级一次性事件，晚加入的订阅者不会再收到
                ui.post { btnRecord.isEnabled = true }
            } else {
                setStatus("预览订阅失败")
            }
        } catch (t: Throwable) {
            setStatus("预览异常: ${t.message}")
            Log.e(TAG, "trySubscribePreview", t)
        }
    }

    private fun safeUnsubscribePreview() {
        val id = previewSubscriberId.also { previewSubscriberId = -1 }
        if (id > 0) runCatching { proxy?.unsubscribe(id) }
    }

    // --- 录制控制 ---

    private fun startRecording() {
        val p = proxy ?: run { setStatus("服务未连接"); return }
        val name = "DVR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val d = DvrSubscriberExample(p)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：直接写入 MediaStore，录制完成后无需扫描即出现在相册
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1) // 写入期间对其他 App 隐藏
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: run { setStatus("MediaStore insert 失败"); return }
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: run { setStatus("无法打开 MediaStore FD"); return }
            currentMediaUri = uri
            currentPfd = pfd
            d.start(pfd.fileDescriptor, WIDTH, HEIGHT)
        } else {
            // Android 9 及以下：写到公共 Movies 目录，录制完用 MediaScanner 通知
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            dir.mkdirs()
            val outFile = File(dir, name)
            currentOutputPath = outFile.absolutePath
            d.start(outFile.absolutePath, WIDTH, HEIGHT)
        }

        dvr = d
        isRecording = true
        recordingStartMs = System.currentTimeMillis()

        btnRecord.text = "停止录制"
        timerText.visibility = View.VISIBLE
        setStatus("录制中…")
        ui.post(timerTick)
        Log.i(TAG, "recording started: $name")
    }

    private fun stopRecording() {
        isRecording = false
        ui.removeCallbacks(timerTick)

        dvr?.stop()
        dvr = null

        btnRecord.text = "开始录制"
        timerText.visibility = View.INVISIBLE
        timerText.text = "00:00"
        setStatus("保存中…")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关闭 FD，再把 IS_PENDING 置 0，文件立即出现在相册
            runCatching { currentPfd?.close() }
            currentPfd = null
            currentMediaUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
                setStatus("已保存到相册")
                Log.i(TAG, "MediaStore entry published: $uri")
            }
            currentMediaUri = null
        } else {
            val path = currentOutputPath ?: return
            currentOutputPath = null
            MediaScannerConnection.scanFile(
                applicationContext, arrayOf(path), arrayOf("video/mp4")
            ) { _, uri ->
                setStatus("已保存: ${File(path).name}")
                Log.i(TAG, "scan done, uri=$uri")
            }
        }
    }

    // --- 工具方法 ---

    private fun pickBackCameraId(p: ICameraProxyService): String? {
        for (id in listOf("0", "1")) {
            runCatching { if (p.getCapabilities(id) != null) return id }
        }
        return null
    }

    private fun setStatus(msg: String) {
        ui.post { statusText.text = msg }
        Log.i(TAG, msg)
    }

    companion object {
        private const val TAG = "DvrActivity"
        private const val WIDTH  = 1280
        private const val HEIGHT = 720
    }
}
