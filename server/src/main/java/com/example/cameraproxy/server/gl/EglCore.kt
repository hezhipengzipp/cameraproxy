package com.example.cameraproxy.server.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Minimal EGL helper. Creates a shared EGLContext for the render thread,
 * plus window / pbuffer surfaces on demand.
 */
class EglCore(sharedContext: EGLContext? = null) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        config = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(
            display, config, sharedContext ?: EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )
        check(context !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}" }
        return eglSurface
    }

    fun createPbufferSurface(w: Int, h: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return eglSurface
    }

    fun releaseSurface(s: EGLSurface) {
        if (s !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s)
    }

    fun makeCurrent(draw: EGLSurface, read: EGLSurface = draw) {
        check(EGL14.eglMakeCurrent(display, draw, read, context)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
    }

    fun swapBuffers(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)

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
