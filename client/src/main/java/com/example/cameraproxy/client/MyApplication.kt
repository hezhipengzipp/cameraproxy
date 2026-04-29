package com.example.cameraproxy.client

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@SuppressLint("StaticFieldLeak")
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkPreviousExitInfo()
        }

        // 注册全局未捕获异常处理器，捕获 Java 层崩溃
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            // 将异常信息持久化，供下次启动的上报使用
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkPreviousExitInfo() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exitReasons = activityManager.getHistoricalProcessExitReasons(null, 0, 5)

        for (info in exitReasons) {
            if (info.pid == android.os.Process.myPid()) continue

            val reason = info.reason
            val description = info.description
            val timestamp = info.timestamp
            val importance = info.importance

            Log.i(TAG, "=== Exit Info === pid=${info.pid}, process=${info.processName}, " +
                    "reason=$reason, importance=$importance, " +
                    "description=${description ?: "N/A"}, timestamp=$timestamp")

            when (reason) {
                ApplicationExitInfo.REASON_CRASH -> {
                    val trace = info.traceInputStream?.bufferedReader()?.readText() ?: ""
                    Log.e(TAG, "上次因 Java 层崩溃退出: ${info.processName}\n$trace")
                    reportCrash("java", info.processName, info.pid, trace)
                }
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                    val trace = info.traceInputStream?.bufferedReader()?.readText() ?: ""
                    Log.e(TAG, "上次因 Native 层崩溃退出: ${info.processName}\n$trace")
                    reportCrash("native", info.processName, info.pid, trace)
                }
                ApplicationExitInfo.REASON_ANR -> {
                    val trace = info.traceInputStream?.bufferedReader()?.readText() ?: ""
                    Log.e(TAG, "上次因 ANR 退出: ${info.processName}\n$trace")
                    reportAnr(info.processName, info.pid, trace)
                }
                ApplicationExitInfo.REASON_LOW_MEMORY -> {
                    Log.w(TAG, "上次因低内存退出: ${info.processName}")
                    logLowMemoryKill(info.processName, info.pid)
                }
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> {
                    Log.w(TAG, "上次因资源过度使用退出: ${info.processName}")
                    logResourceKill(info.processName, info.pid)
                }
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED -> {
                    Log.d(TAG, "用户主动停止: ${info.processName}")
                }
                ApplicationExitInfo.REASON_SIGNALED -> {
                    Log.w(TAG, "上次因信号退出: ${info.processName}, status=${info.status}")
                }
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> {
                    Log.e(TAG, "上次因初始化失败退出: ${info.processName}")
                }
                ApplicationExitInfo.REASON_OTHER -> {
                    Log.d(TAG, "上次因其他原因退出: ${info.processName}")
                }
                else -> {
                    Log.d(TAG, "上次退出原因: reason=$reason, ${info.processName}")
                }
            }
        }
    }

    private fun reportCrash(type: String, processName: String, pid: Int, trace: String) {
        // TODO: 接入崩溃上报 SDK（如 Bugly、Firebase Crashlytics）时，在此处上报
        Log.i(TAG, "[CrashReport] type=$type, process=$processName, pid=$pid")
    }

    private fun reportAnr(processName: String, pid: Int, trace: String) {
        // TODO: 接入 ANR 上报 SDK 时，在此处上报
        Log.i(TAG, "[AnrReport] process=$processName, pid=$pid")
    }

    private fun logLowMemoryKill(processName: String, pid: Int) {
        // TODO: 接入 APM 平台时，在此处上报低内存事件
        Log.w(TAG, "[LowMemoryKill] process=$processName, pid=$pid")
    }

    private fun logResourceKill(processName: String, pid: Int) {
        // TODO: 接入 APM 平台时，在此处上报资源过度使用事件
        Log.w(TAG, "[ResourceKill] process=$processName, pid=$pid")
    }

    companion object {
        private const val TAG = "ProxyClientApp"

        lateinit var instance: MyApplication
            private set
    }
}
