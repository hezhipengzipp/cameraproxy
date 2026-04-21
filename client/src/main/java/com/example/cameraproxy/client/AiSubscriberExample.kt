package com.example.cameraproxy.client

import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService

/**
 * AI 识别示例：用 ImageReader.getSurface() 订阅相机代理。
 *
 * 注意：ImageReader 必须用 PixelFormat.RGBA_8888，不能用 YUV_420_888。
 * 原因：CameraEngine GL 线程渲染输出的是 RGBA，如果 ImageReader 声明 YUV 格式，
 * BufferQueue 格式不匹配会导致数据乱码或崩溃。
 *
 * 如果 AI 模型要求 YUV 输入，在 onFrame 回调里用 RGBA→YUV CPU 转换（示例见下方注释）。
 */
class AiSubscriberExample(private val proxy: ICameraProxyService) {

    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    private var subscriberId = -1

    private val callback = object : ICameraCallback.Stub() {
        override fun onFrameStart() = Unit
        override fun onError(code: Int, msg: String?) { Log.e(TAG, "camera error $code: $msg") }
        override fun onCameraClosed() = stop()
        override fun onConfigChanged(w: Int, h: Int) = Unit
    }

    /**
     * 开始订阅。
     *
     * @param width    帧宽度
     * @param height   帧高度
     * @param onFrame  每帧回调，Image 用完必须调 image.close()，否则 BufferQueue 会堵死
     */
    fun start(width: Int, height: Int, onFrame: (Image) -> Unit) {
        // 起独立线程接收 ImageReader 回调，避免阻塞 GL 渲染线程
        readerThread = HandlerThread("ai-reader").apply { start() }
        readerHandler = Handler(readerThread!!.looper)

        // 必须用 RGBA_8888，与 GL 渲染输出格式一致
        imageReader = ImageReader.newInstance(
            width, height,
            android.graphics.PixelFormat.RGBA_8888,
            2  // maxImages=2，双缓冲够用，太大会占内存
        ).apply {
            setOnImageAvailableListener({ reader ->
                // acquireLatestImage 会丢弃来不及处理的旧帧，保证实时性
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    onFrame(image)
                } finally {
                    image.close() // 必须 close，否则 maxImages 耗尽后 acquireLatestImage 返回 null
                }
            }, readerHandler)
        }

        subscriberId = proxy.subscribe(imageReader!!.surface, callback)
        Log.i(TAG, "AI subscriber started, id=$subscriberId, size=${width}x${height}")
    }

    fun stop() {
        val id = subscriberId.also { subscriberId = -1 }
        if (id > 0) runCatching { proxy.unsubscribe(id) }

        imageReader?.close()
        imageReader = null

        readerThread?.quitSafely()
        readerThread = null
        Log.i(TAG, "AI subscriber stopped")
    }

    companion object {
        private const val TAG = "AiSubscriber"

        /**
         * RGBA → NV21 (YUV420SP) 转换，供需要 YUV 输入的模型使用。
         *
         * 调用示例（在 onFrame 回调里）：
         *   val plane = image.planes[0]
         *   val rgba = ByteArray(plane.rowStride * image.height)
         *   plane.buffer.get(rgba)
         *   val nv21 = rgbaToNv21(rgba, image.width, image.height, plane.rowStride)
         */
        fun rgbaToNv21(rgba: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
            val yuvSize = width * height * 3 / 2
            val nv21 = ByteArray(yuvSize)
            var yIndex = 0
            var uvIndex = width * height

            for (row in 0 until height) {
                for (col in 0 until width) {
                    val base = row * rowStride + col * 4
                    val r = rgba[base].toInt() and 0xFF
                    val g = rgba[base + 1].toInt() and 0xFF
                    val b = rgba[base + 2].toInt() and 0xFF

                    // BT.601 full-range
                    nv21[yIndex++] = ((66 * r + 129 * g + 25 * b + 128) shr 8 + 16).toByte()

                    if (row % 2 == 0 && col % 2 == 0) {
                        nv21[uvIndex++] = ((-38 * r - 74 * g + 112 * b + 128) shr 8 + 128).toByte() // V
                        nv21[uvIndex++] = ((112 * r - 94 * g - 18 * b + 128) shr 8 + 128).toByte() // U
                    }
                }
            }
            return nv21
        }
    }
}
