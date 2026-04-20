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
 * 进程内唯一的 Service，独占相机并把画面分发给多个 Client。
 *
 * ## 部署方式
 * 在 AndroidManifest 里声明了 android:process=":camera"，
 * Service 会被拉起到一个**独立进程**里。这样做有两个好处：
 *   1. 隔离 —— UI 进程崩溃不会拖垮相机；
 *   2. 资源集中 —— 所有 Client 的相机数据都汇聚到这一个进程，
 *      只开一次相机、通过 GL fan-out 分发。
 *
 * ## 生命周期
 *   onCreate   : 启前台通知（Android 12+ 要求相机前台服务必须挂 camera 类型）
 *   onBind     : 返回 AIDL Binder，供 Client bindService 取到
 *   onDestroy  : 清理所有订阅者 + 关相机
 *
 * ## 角色定位
 * 本 Service 是**协调层**：
 *   - 真正的相机 + GL 逻辑在 CameraEngine；
 *   - 本类做三件事：Binder 入口、订阅者档案（SubscriberRecord）管理、
 *                   控制权（exposure 等）的 CAS 仲裁。
 */
class CameraHolderService : Service() {

    /** 懒加载相机引擎，第一次有 Client 订阅时才真正初始化。 */
    private val engine by lazy {
        CameraEngine(applicationContext, engineListener)
    }

    /**
     * 订阅者档案表：engineId -> SubscriberRecord。
     * ID 由 CameraEngine 生成（从 1 开始自增），本 Service 直接沿用作 key。
     */
    private val records = ConcurrentHashMap<Int, SubscriberRecord>()

    /**
     * 当前独占控制权持有者的 ID（null 表示无人持有）。
     * 用 AtomicReference 是为了用 compareAndSet 做原子互斥 —— 多个 Client
     * 同时 requestControl 时只有一个能成功。
     */
    private val controlOwner = AtomicReference<Int?>(null)

    /**
     * 一个订阅者的完整档案：
     *   - callback       ：Client 实现的 AIDL 回调接口（跨进程 Binder 代理）
     *   - pid            ：Client 进程 ID，只用来打日志
     *   - deathRecipient ：Binder 死亡监听器，Client 进程崩溃时触发
     */
    private class SubscriberRecord(
        val id: Int,
        val callback: ICameraCallback,
        val pid: Int,
        val deathRecipient: IBinder.DeathRecipient
    )

    /**
     * 从 CameraEngine 收到的事件 → 广播给所有订阅者。
     * safeCall 包一层是为了某个 Client 挂掉时自动清理它，不影响其他人。
     */
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
        // Android 14+ 对前台服务类型管控很严，相机必须声明 foregroundServiceType=camera
        startForegroundWithCameraType()
    }

    /** Client bindService 后拿到的 Binder —— 就是下面那个 AIDL Stub 实现。 */
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Service 即将销毁，主动通知所有订阅者 + 关相机
        records.values.toList().forEach { unlinkAndForget(it) }
        records.clear()
        engine.closeCamera()
        super.onDestroy()
    }

    // region AIDL

    /**
     * AIDL 接口的实现。每一个方法都会运行在 Binder 线程池上
     * （不是主线程），所以可以安全地做同步操作。
     */
    private val binder = object : ICameraProxyService.Stub() {

        override fun openCamera(cameraId: String, width: Int, height: Int, format: Int): Boolean {
            // 双重保险：AndroidManifest 已声明权限，这里再校验运行时是否已授予
            requireCameraPermission()
            return engine.openCamera(cameraId, width, height, format)
        }

        override fun closeCamera() {
            // 只有在**无人订阅**时才真正关闭，避免 A 调 closeCamera 把 B 的预览也关了
            if (records.isEmpty()) engine.closeCamera()
        }

        override fun subscribe(surface: Surface?, callback: ICameraCallback?): Int {
            // 参数防御
            if (surface == null || callback == null || !surface.isValid) return -1

            // Binder.getCallingPid() 拿到的是**对端进程**的 pid，不是本进程
            val pid = Binder.getCallingPid()

            // 1. 先让 CameraEngine 建好 EGLWindowSurface 并分配 ID
            val engineId = engine.addSubscriber(surface)
            if (engineId == -1) {
                Log.w(TAG, "engine.addSubscriber failed (camera not open?)")
                return -1
            }

            // 2. 监听 Client 进程死亡。只要 Client 进程崩了 / 被杀了，
            //    这个回调会在 Binder 线程触发，我们可以自动清理资源。
            val deathRecipient = IBinder.DeathRecipient {
                Log.w(TAG, "client died, auto-unsubscribing id=$engineId pid=$pid")
                internalUnsubscribe(engineId)
            }
            try {
                // linkToDeath 绑定的是 Binder 本身，不是某个方法
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (t: Throwable) {
                // Client 进程可能在这中间已经崩了，linkToDeath 抛异常
                Log.e(TAG, "linkToDeath failed", t)
                engine.removeSubscriber(engineId)
                return -1
            }

            // 3. 记下档案，返回 ID
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

        /**
         * 请求独占控制权。
         * controlOwner.compareAndSet(null, id) —— 只有当前持有者为 null 时
         * 才原子地设为 id，保证同一时刻最多一个 owner。
         */
        override fun requestControl(subscriberId: Int): Boolean {
            if (!records.containsKey(subscriberId)) return false
            return controlOwner.compareAndSet(null, subscriberId)
        }

        override fun setExposure(subscriberId: Int, value: Int) {
            if (controlOwner.get() != subscriberId) {
                throw SecurityException("subscriber $subscriberId is not the control owner")
            }
            // TODO: 真正应用曝光值 —— 需要在 CameraEngine 里重发一个带
            //       CONTROL_AE_EXPOSURE_COMPENSATION 的 RepeatingRequest。
            //       本脚手架先留空，只记日志。
            Log.i(TAG, "setExposure($value) by $subscriberId (no-op placeholder)")
        }

        /**
         * 释放控制权 —— 只有当前持有者才能释放。
         * 用 CAS(id -> null) 防止"A 释放了 B 的控制权"这种误操作。
         */
        override fun releaseControl(subscriberId: Int) {
            controlOwner.compareAndSet(subscriberId, null)
        }
    }

    /**
     * 所有取消订阅路径的统一入口（主动 unsubscribe / 死亡回调 / 回调调用失败 都走这里）。
     *
     * 步骤：
     *   1. 从档案表移除；
     *   2. 解绑死亡监听（linkToDeath 和 unlinkToDeath 要配对）；
     *   3. 通知引擎释放 GL 资源；
     *   4. 清理控制权（如果这个 Client 正持有）；
     *   5. 如果没人订阅了，顺手关相机省电。
     */
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

    /** 解除死亡监听。Binder 已死时 unlinkToDeath 会抛异常，吞掉即可。 */
    private fun unlinkAndForget(r: SubscriberRecord) {
        try { r.callback.asBinder().unlinkToDeath(r.deathRecipient, 0) } catch (_: Throwable) {}
    }

    /**
     * 调用 Client 回调时的防御性封装：
     * Client 进程崩了还没来得及触发 deathRecipient 时，callback.xxx() 会抛
     * DeadObjectException —— 捕获后就地清理，不让一个坏 Client 拖累全体。
     */
    private fun safeCall(r: SubscriberRecord, block: (ICameraCallback) -> Unit) {
        try { block(r.callback) }
        catch (t: Throwable) {
            Log.w(TAG, "callback failed for ${r.id}, removing", t)
            internalUnsubscribe(r.id)
        }
    }

    /**
     * 检查 Server 自身是否拿到 CAMERA 权限。
     * 注意：即使 Client 有权限，Camera2 会校验的是**调用进程**的权限 —— 而 openCamera
     * 是 Server 进程调的，所以 Server 必须自己有 CAMERA 权限（MainActivity 里动态申请）。
     */
    private fun requireCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw SecurityException("server does not hold CAMERA permission")
    }

    // endregion

    /**
     * 以"相机"类型启动前台服务。
     *
     * 分版本处理：
     *   - Android 8+  ：必须先创建 NotificationChannel；
     *   - Android 14+（UPSIDE_DOWN_CAKE）：startForeground 时必须指定
     *                  FOREGROUND_SERVICE_TYPE_CAMERA，否则系统抛异常。
     */
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
