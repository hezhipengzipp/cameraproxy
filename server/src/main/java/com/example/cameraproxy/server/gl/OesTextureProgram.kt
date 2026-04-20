package com.example.cameraproxy.server.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 专门绘制 OES 外部纹理（即 SurfaceTexture）的 Shader 程序。
 *
 * 工作流程：
 *   1. createOesTextureId() 生成一个 OES 纹理 ID，交给 SurfaceTexture；
 *   2. Camera2 把每帧写入这个 SurfaceTexture；
 *   3. draw(textureId, stMatrix) 把这帧画成全屏四边形，铺满当前 EGLSurface；
 *   4. CameraEngine 切到下一个订阅者的 EGLSurface，再 draw 一次 —— 这就是 fan-out。
 *
 * 为什么要 Shader？
 *   OES 纹理不能被 glDraw* 直接"贴"到屏幕上，必须经过自定义 Shader 采样，
 *   而且 Shader 里要用特殊的 samplerExternalOES 类型。下面 FRAG 里能看到。
 */
class OesTextureProgram {

    // GL "program" 是 vertex + fragment shader 连接后的产物
    private val program: Int

    // Shader 里声明的 attribute / uniform 在 GL 内部的地址（int 句柄）
    private val aPos: Int        // 顶点位置
    private val aTex: Int        // 顶点纹理坐标
    private val uMvp: Int        // 模型视图投影矩阵（本项目用单位矩阵）
    private val uStMatrix: Int   // SurfaceTexture 提供的变换矩阵（修正翻转/旋转）
    private val uSampler: Int    // OES 纹理采样器

    /**
     * 顶点数据 —— 4 个顶点的 (x, y, u, v) 交错排列，构成一个屏幕空间的正方形。
     * 用 DirectByteBuffer 是 OpenGL 的硬性要求：数据必须在 native 堆上，
     * 且字节序是 native order。
     */
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4) // 每个 float 4 字节
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD_COORDS); position(0) }

    init {
        // 编译 + 链接 Shader，拿到 attribute / uniform 的句柄
        program = buildProgram(VERT, FRAG)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
    }

    /**
     * 生成一个 OES 外部纹理 ID。
     *
     * 和普通 glGenTextures 的区别：绑定目标用 GL_TEXTURE_EXTERNAL_OES 而非 GL_TEXTURE_2D，
     * 这样才能被 SurfaceTexture 当作消费端、让 Camera HAL 的 GraphicBuffer 直接映射进来
     * （零拷贝）。
     *
     * 参数约束（踩坑点）：
     *   - OES 纹理不支持 mipmap，所以 MIN_FILTER 只能用 GL_LINEAR 或 GL_NEAREST；
     *   - 不支持 GL_REPEAT，只能用 GL_CLAMP_TO_EDGE；
     *   违反这些约束 glTexImage 会返回错误。
     */
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

    /**
     * 把 OES 纹理画成一个铺满当前 EGLSurface 的矩形。
     *
     * @param textureId OES 纹理 ID（从 createOesTextureId 拿到的那个）
     * @param stMatrix  SurfaceTexture.getTransformMatrix 拿到的 4x4 矩阵，
     *                  用来抵消传感器 / HAL 的坐标翻转 —— 不用这个矩阵画面会倒。
     * @param mvp       模型视图投影矩阵，本项目画全屏矩形，直接用单位矩阵。
     */
    fun draw(textureId: Int, stMatrix: FloatArray, mvp: FloatArray = IDENTITY) {
        // 1. 激活 Shader
        GLES20.glUseProgram(program)

        // 2. 绑定纹理到 TEXTURE0 槽，并告诉 sampler 去哪个槽采样
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSampler, 0) // 0 = TEXTURE0

        // 3. 上传两个变换矩阵
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)

        // 4. 绑定顶点数据：每个顶点 4 float = 16 字节 stride
        //    前 2 float 是 (x,y)，后 2 float 是 (u,v)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)

        // 从偏移 2 开始读纹理坐标
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)

        // 5. 用 4 个顶点画一个三角形带 = 一个矩形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 6. 清理状态，避免污染下一次绘制
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /** 释放 GL program。关闭相机时调用。 */
    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        /** 4x4 单位矩阵 —— 表示"不做任何变换"。 */
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        /**
         * 全屏四边形的顶点：屏幕空间 (-1,-1) ~ (1,1) 覆盖整个视口；
         * 纹理坐标 (0,0) ~ (1,1) 采样整张纹理。
         * 每行：x, y,  u, v
         */
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 0f,  // 左下
             1f, -1f, 1f, 0f,  // 右下
            -1f,  1f, 0f, 1f,  // 左上
             1f,  1f, 1f, 1f,  // 右上
        )

        /**
         * 顶点 Shader：把顶点坐标直接输出，纹理坐标乘以 stMatrix 做修正。
         * stMatrix 是一个 4x4 矩阵，虽然我们只用 (u, v)，但数学上写成 vec4 一起算更通用。
         */
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

        /**
         * 片段 Shader：OES 外部纹理采样。
         *
         * 关键两行：
         *   - #extension GL_OES_EGL_image_external : require   声明使用 OES 扩展
         *   - uniform samplerExternalOES uSampler              专用的采样器类型
         * 这两行不写，编译不过。
         */
        private const val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uSampler;
            void main() { gl_FragColor = texture2D(uSampler, vTex); }
        """

        /** 编译单个 Shader，失败时把日志打印出来并抛异常。 */
        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        /** 编译 vertex + fragment 并链接成一个 GL program。 */
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
            // 链接完成后单独的 shader 对象可以删 —— program 已经拷贝过它们
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }
    }
}
