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
 * Minimal launcher that ensures runtime CAMERA permission is granted
 * and starts the foreground CameraHolderService.
 */
class MainActivity : AppCompatActivity() {

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
            stopService(Intent(this, CameraHolderService::class.java))
        }
    }

    private fun ensurePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startService()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startService() {
        val intent = Intent(this, CameraHolderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        findViewById<TextView>(R.id.status).text = "service started"
    }
}
