package com.example.cameraproxy.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.cameraproxy.CameraCapabilities
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-unique service that holds the Camera and fans out frames.
 *
 * Runs in :camera process (see AndroidManifest). One instance, N clients.
 */
class CameraHolderService : Service() {

    private val engine by lazy {
        CameraEngine(applicationContext, engineListener)
    }

    /** id -> SubscriberRecord (lifecycle, callback, deathRecipient) */
    private val records = ConcurrentHashMap<Int, SubscriberRecord>()

    /** Current exclusive control owner, or null. */
    private val controlOwner = AtomicReference<Int?>(null)

    private class SubscriberRecord(
        val id: Int,
        val callback: ICameraCallback,
        val pid: Int,
        val deathRecipient: IBinder.DeathRecipient
    )

    private val engineListener = object : CameraEngine.Listener {
        override fun onFirstFrame() {
            records.values.forEach { r -> safeCall(r) { it.onFrameStart() } }
        }
        override fun onEngineError(code: Int, msg: String) {
            records.values.forEach { r -> safeCall(r) { it.onError(code, msg) } }
        }
        override fun onCameraClosedByHal() {
            records.values.forEach { r -> safeCall(r) { it.onCameraClosed() } }
        }
        override fun onResolutionChanged(w: Int, h: Int) {
            records.values.forEach { r -> safeCall(r) { it.onConfigChanged(w, h) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithCameraType()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Clean up subscribers -- we're going down.
        records.values.toList().forEach { unlinkAndForget(it) }
        records.clear()
        engine.closeCamera()
        super.onDestroy()
    }

    // region AIDL

    private val binder = object : ICameraProxyService.Stub() {

        override fun openCamera(cameraId: String, width: Int, height: Int, format: Int): Boolean {
            requireCameraPermission()
            return engine.openCamera(cameraId, width, height, format)
        }

        override fun closeCamera() {
            if (records.isEmpty()) engine.closeCamera()
        }

        override fun subscribe(surface: Surface?, callback: ICameraCallback?): Int {
            if (surface == null || callback == null || !surface.isValid) return -1

            val pid = Binder.getCallingPid()
            val engineId = engine.addSubscriber(surface)
            if (engineId == -1) {
                Log.w(TAG, "engine.addSubscriber failed (camera not open?)")
                return -1
            }

            val deathRecipient = IBinder.DeathRecipient {
                Log.w(TAG, "client died, auto-unsubscribing id=$engineId pid=$pid")
                internalUnsubscribe(engineId)
            }
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (t: Throwable) {
                Log.e(TAG, "linkToDeath failed", t)
                engine.removeSubscriber(engineId)
                return -1
            }

            records[engineId] = SubscriberRecord(engineId, callback, pid, deathRecipient)
            return engineId
        }

        override fun unsubscribe(subscriberId: Int) {
            internalUnsubscribe(subscriberId)
        }

        override fun getCapabilities(cameraId: String?): CameraCapabilities? {
            if (cameraId == null) return null
            return engine.buildCapabilities(cameraId)
        }

        override fun requestControl(subscriberId: Int): Boolean {
            if (!records.containsKey(subscriberId)) return false
            return controlOwner.compareAndSet(null, subscriberId)
        }

        override fun setExposure(subscriberId: Int, value: Int) {
            if (controlOwner.get() != subscriberId) {
                throw SecurityException("subscriber $subscriberId is not the control owner")
            }
            // TODO: apply exposure via a new RepeatingRequest. Out of scope for this scaffold --
            //       plug into engine via a method like engine.setExposure(value).
            Log.i(TAG, "setExposure($value) by $subscriberId (no-op placeholder)")
        }

        override fun releaseControl(subscriberId: Int) {
            controlOwner.compareAndSet(subscriberId, null)
        }
    }

    private fun internalUnsubscribe(id: Int) {
        val r = records.remove(id) ?: return
        unlinkAndForget(r)
        engine.removeSubscriber(id)
        controlOwner.compareAndSet(id, null)
        if (records.isEmpty()) {
            Log.i(TAG, "no subscribers left, closing camera")
            engine.closeCamera()
        }
    }

    private fun unlinkAndForget(r: SubscriberRecord) {
        try { r.callback.asBinder().unlinkToDeath(r.deathRecipient, 0) } catch (_: Throwable) {}
    }

    private fun safeCall(r: SubscriberRecord, block: (ICameraCallback) -> Unit) {
        try { block(r.callback) }
        catch (t: Throwable) {
            Log.w(TAG, "callback failed for ${r.id}, removing", t)
            internalUnsubscribe(r.id)
        }
    }

    private fun requireCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw SecurityException("server does not hold CAMERA permission")
    }

    // endregion

    private fun startForegroundWithCameraType() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera Proxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Camera Proxy")
            .setContentText("Sharing camera to subscribed apps")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "CameraHolderService"
        private const val CHANNEL = "cameraproxy"
        private const val NOTIF_ID = 1
    }
}
