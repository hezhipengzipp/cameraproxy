package com.example.cameraproxy.client

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService

/**
 * Subscribes to the shared camera proxy service and renders frames into a
 * SurfaceView. The service draws directly into our Surface via GL -- no
 * byte buffers cross Binder.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var proxy: ICameraProxyService? = null
    private var subscriberId: Int = -1
    private var pendingSurface: SurfaceHolder? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            proxy = ICameraProxyService.Stub.asInterface(service)
            setStatus("bound to proxy")
            // If the SurfaceView was ready before the service connected, subscribe now.
            pendingSurface?.let { trySubscribe(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            proxy = null
            subscriberId = -1
            setStatus("proxy disconnected")
        }
    }

    private val callback = object : ICameraCallback.Stub() {
        override fun onFrameStart() { setStatus("first frame") }
        override fun onError(code: Int, msg: String?) { setStatus("error $code: $msg") }
        override fun onCameraClosed() { setStatus("camera closed") }
        override fun onConfigChanged(width: Int, height: Int) {
            setStatus("config changed ${width}x${height}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        status = findViewById(R.id.status)
        findViewById<SurfaceView>(R.id.preview).holder.addCallback(this)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent("com.example.cameraproxy.ACTION_BIND").apply {
            setPackage("com.example.cameraproxy.server")
        }
        val ok = bindService(intent, connection, BIND_AUTO_CREATE)
        setStatus(if (ok) "binding..." else "bindService returned false")
    }

    override fun onStop() {
        super.onStop()
        safeUnsubscribe()
        try { unbindService(connection) } catch (_: Throwable) {}
        proxy = null
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        pendingSurface = holder
        trySubscribe(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Size handed to us by the layout; server queries it lazily when drawing.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pendingSurface = null
        safeUnsubscribe()
    }

    // --- Impl ---

    private fun trySubscribe(holder: SurfaceHolder) {
        val p = proxy ?: return
        if (subscriberId != -1) return
        try {
            val cameraId = pickBackCameraId(p) ?: run {
                setStatus("no camera id")
                return
            }
            if (!p.openCamera(cameraId, 1280, 720, ImageFormat.PRIVATE)) {
                setStatus("openCamera failed"); return
            }
            subscriberId = p.subscribe(holder.surface, callback)
            setStatus(if (subscriberId > 0) "subscribed id=$subscriberId" else "subscribe failed")
        } catch (t: Throwable) {
            Log.e(TAG, "subscribe failed", t)
            setStatus("subscribe exception: ${t.message}")
        }
    }

    private fun safeUnsubscribe() {
        val p = proxy
        val id = subscriberId
        subscriberId = -1
        if (p != null && id > 0) {
            try { p.unsubscribe(id) } catch (_: Throwable) {}
        }
    }

    private fun pickBackCameraId(p: ICameraProxyService): String? {
        // Cheap heuristic: ask for "0" first, fall back to "1".
        val tryIds = listOf("0", "1")
        for (id in tryIds) {
            try {
                if (p.getCapabilities(id) != null) return id
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun setStatus(msg: String) {
        ui.post { status.text = msg }
        Log.i(TAG, msg)
    }

    companion object { private const val TAG = "ProxyClient" }
}
