package com.example.cameraproxy.server.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * EGL 极简封装。
 *
 * ## 背景知识：EGL 是什么？
 * OpenGL ES 本身只管"画什么"，不管"画到哪"。把 GL 画出来的内容送到屏幕/
 * Surface 是 EGL 的职责。EGL 提供三个核心对象：
 *
 *   - EGLDisplay : 渲染目标所在的"显示设备"（通常就是默认屏幕）。
 *   - EGLContext : OpenGL 状态机（当前绑定的纹理、Shader、矩阵等）。
 *   - EGLSurface : 画板 —— 可以是窗口（WindowSurface，关联一个 android.view.Surface）
 *                         也可以是离屏（PbufferSurface，画完不显示，用来做中间计算）。
 *
 * 调 GL 命令前必须先 eglMakeCurrent(display, draw, read, context) —— 把
 * 「画板 + 状态机」绑到**当前线程**上。之后所有 GLES20.xxx 调用都会落在
 * 这个 context + surface 组合上。
 *
 * 本类做的事：在构造时建好 Display 和 Context，按需创建 / 销毁 Surface。
 *
 * @param sharedContext  可选的共享 Context。如果传入，本 Context 会和它共享
 *                       纹理 / Program 等资源 —— 多线程同时用 GL 时用得上。
 *                       本项目里单 GL 线程，不需要共享，所以传默认 null。
 */
class EglCore(sharedContext: EGLContext? = null) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        // 1. 拿到默认的 EGLDisplay（相当于整个 GL 的"会话句柄"）。
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        // 2. 初始化 Display。版本号通过 out 参数返回，本项目不关心具体版本。
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // 3. 选 EGLConfig —— 描述"画板"的格式：RGBA 各 8 位、支持 GLES2。
        //    attribs 是「key, value, key, value, ..., EGL_NONE」的结构。
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 4, // 4 = EGL_OPENGL_ES2_BIT（我们用 GLES 2.0）
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        config = configs[0]

        // 4. 创建 EGLContext（GL 状态机）。CLIENT_VERSION=2 表示 GLES 2.0。
        //    sharedContext 不为空时资源共享，这里一般传 EGL_NO_CONTEXT。
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(
            display, config, sharedContext ?: EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )
        check(context !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    /**
     * 创建一个"窗口"画板 —— 把 GL 输出直接显示到给定的 Android Surface。
     * 本项目里每个订阅者都会对应一个 WindowSurface，swapBuffers 后画面就出现在
     * Client 的 SurfaceView 上（跨进程，Surface 代理会把 buffer 送回 Client 进程）。
     */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}" }
        return eglSurface
    }

    /**
     * 创建一个离屏画板（pixel buffer）。画到它上面的内容不会显示到任何地方，
     * 但能让 GL context "有地方可以 makeCurrent"。
     *
     * 本项目用它做什么？
     * SurfaceTexture.updateTexImage() 要求**当前线程有活的 GL context**。
     * 还没有任何订阅者时也需要把相机帧吞掉（否则 BufferQueue 会堵塞），
     * 所以用一个 1x1 的 Pbuffer 作"dummy 画板"垫底。见 CameraEngine.dummyPbuffer。
     */
    fun createPbufferSurface(w: Int, h: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return eglSurface
    }

    /** 销毁 EGLSurface —— 订阅者退订时调用，释放 GPU 资源。 */
    fun releaseSurface(s: EGLSurface) {
        if (s !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s)
    }

    /**
     * 把「画板 + Context」绑定到**当前线程**。
     *
     * draw: 绘制目标；read: 读取目标（glReadPixels 之类）。默认两者相同。
     *
     * 关键点：eglMakeCurrent 是 EGL 里唯一让 GL 命令"生效"的开关。
     * 任何 GLES20.xxx 调用前都必须先 makeCurrent，否则要么崩溃要么无效。
     * Fan-out 多订阅者就是靠反复 makeCurrent 切换 draw 目标实现的。
     */
    fun makeCurrent(draw: EGLSurface, read: EGLSurface = draw) {
        check(EGL14.eglMakeCurrent(display, draw, read, context)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    /** 解绑当前线程的 Context。release 前必须先解绑，否则资源可能泄漏。 */
    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
    }

    /**
     * 把当前帧从 EGLSurface 的"后缓冲"交换到"前缓冲"，也就是把画好的内容
     * 真正提交出去 —— WindowSurface 会把它送到订阅者的 Surface。
     * 所有 glDraw* 命令之后必须调一次 swapBuffers，画面才会出现。
     */
    fun swapBuffers(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)

    /** 彻底清理 EGL 资源 —— 关闭相机 / 销毁 Service 时调用。 */
    fun release() {
        if (display !== EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    companion object {
        private const val TAG = "EglCore"
    }
}
