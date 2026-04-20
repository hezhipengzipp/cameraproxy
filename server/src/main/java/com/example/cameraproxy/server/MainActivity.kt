package com.example.cameraproxy.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Server 端的入口 Activity —— 只是一个启动面板。
 *
 * 职责：
 *   1. 运行时申请 CAMERA 权限（必须由 Activity 发起，Service 不能直接申请）；
 *   2. 启动前台服务 CameraHolderService；
 *   3. 提供"停止服务"按钮做调试用。
 *
 * 注意：Activity 本身**不持有**相机 —— 所有相机逻辑都在独立进程 :camera 的
 * CameraHolderService 里。Activity 关了服务依然跑，Client 仍可订阅。
 */
class MainActivity : AppCompatActivity() {

    /**
     * 现代权限申请方式（ActivityResult API）。
     * registerForActivityResult 必须在 onCreate 之前注册（字段初始化阶段最稳），
     * 否则会抛 LifecycleException。
     */
    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        findViewById<TextView>(R.id.status).text =
            if (granted) "CAMERA granted" else "CAMERA denied"
        if (granted) startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        findViewById<Button>(R.id.btn_start).setOnClickListener { ensurePermissionAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            // stopService 会走到 CameraHolderService.onDestroy，自动清理订阅者
            stopService(Intent(this, CameraHolderService::class.java))
        }
    }

    /** 已授权直接启服务，没授权就弹系统权限对话框。 */
    private fun ensurePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startService()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    /**
     * 用 startForegroundService 启动（而不是普通 startService）。
     * Android 8+ 要求：后台启动的 Service 必须在 5 秒内调 startForeground
     * 提升为前台服务，否则系统会直接 kill。CameraHolderService.onCreate 里
     * 已经做了 startForeground，所以这里是安全的。
     */
    private fun startService() {
        val intent = Intent(this, CameraHolderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        findViewById<TextView>(R.id.status).text = "service started"
    }
}
