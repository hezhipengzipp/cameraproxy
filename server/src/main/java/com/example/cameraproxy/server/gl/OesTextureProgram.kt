package com.example.cameraproxy.server.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws an OES external texture (SurfaceTexture) onto the current EGLSurface
 * as a full-screen quad, using a per-frame transform matrix.
 */
class OesTextureProgram {

    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uMvp: Int
    private val uStMatrix: Int
    private val uSampler: Int

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD_COORDS); position(0) }

    init {
        program = buildProgram(VERT, FRAG)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
    }

    fun createOesTextureId(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, tex[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun draw(textureId: Int, stMatrix: FloatArray, mvp: FloatArray = IDENTITY) {
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSampler, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)

        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // x,y, u,v
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )

        private const val VERT = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uMvp;
            uniform mat4 uStMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
                vTex = (uStMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uSampler;
            void main() { gl_FragColor = texture2D(uSampler, vTex); }
        """

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        private fun buildProgram(v: String, f: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, v)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, f)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }
    }
}
