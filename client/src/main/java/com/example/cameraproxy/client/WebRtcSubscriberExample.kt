package com.example.cameraproxy.client

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService

/**
 * WebRTC 推流示例。WebRTC 需要编码后的视频流，有两种接入方式：
 *
 * ## 方式 A：SurfaceTexture（适配 WebRTC 官方 SurfaceTextureHelper）
 *
 *   WebRTC SDK 内部的 SurfaceTextureHelper 会创建一个 SurfaceTexture，你把对应
 *   的 Surface 传给 proxy.subscribe()，GL 线程渲染进去后，SurfaceTextureHelper
 *   在自己的 GL 线程 updateTexImage() 拿到 OES 纹理，再转成 I420 ByteBuffer 或
 *   VideoFrame(texture)，送进 WebRTC 编码管线。
 *
 *   如果你用的是 org.webrtc:google-webrtc 这个官方 aar，用 SurfaceTextureHelper
 *   方式最自然，接口完全匹配。
 *
 * ## 方式 B：MediaCodec InputSurface（不依赖 WebRTC SDK 内部 GL）
 *
 *   用硬件编码器产出 H.264 NAL 单元，再把这些裸流喂给 WebRTC 的
 *   ExternalVideoEncoderFactory / EncodedVideoSender 接口。
 *   好处是不需要 WebRTC SDK 的 GL 上下文，适合深度定制的集成场景。
 */
class WebRtcSubscriberExample(private val proxy: ICameraProxyService) {

    // -------------------------------------------------------------------------
    // 方式 A：SurfaceTexture 方式
    // -------------------------------------------------------------------------

    private var surfaceTexture: SurfaceTexture? = null
    private var textureSurface: Surface? = null
    private var textureThread: HandlerThread? = null
    private var textureHandler: Handler? = null
    private var subscriberIdA = -1

    private val callbackA = object : ICameraCallback.Stub() {
        override fun onFrameStart() = Unit
        override fun onError(code: Int, msg: String?) { Log.e(TAG, "camera error $code: $msg") }
        override fun onCameraClosed() = stopSurfaceTexture()
        override fun onConfigChanged(w: Int, h: Int) = Unit
    }

    /**
     * 方式 A 启动。
     *
     * @param oesTextureId  由 WebRTC SurfaceTextureHelper 提供的 OES 纹理 ID。
     *                      如果是自己管 GL，先 glGenTextures 一个 ID 传进来。
     * @param width / height 视频尺寸
     * @param onFrameAvailable  每帧通知回调（在 textureThread 上触发），
     *                          在此回调里调 surfaceTexture.updateTexImage() + WebRTC 帧提交。
     */
    fun startSurfaceTexture(
        oesTextureId: Int,
        width: Int,
        height: Int,
        onFrameAvailable: (SurfaceTexture) -> Unit
    ) {
        textureThread = HandlerThread("webrtc-tex").apply { start() }
        textureHandler = Handler(textureThread!!.looper)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            // 每帧到来通知 WebRTC 的 GL 线程去 updateTexImage
            setOnFrameAvailableListener({ st -> onFrameAvailable(st) }, textureHandler)
        }
        textureSurface = Surface(surfaceTexture)

        subscriberIdA = proxy.subscribe(textureSurface!!, callbackA)
        Log.i(TAG, "WebRTC SurfaceTexture subscriber started, id=$subscriberIdA")
    }

    fun stopSurfaceTexture() {
        val id = subscriberIdA.also { subscriberIdA = -1 }
        if (id > 0) runCatching { proxy.unsubscribe(id) }

        textureSurface?.release(); textureSurface = null
        surfaceTexture?.release(); surfaceTexture = null
        textureThread?.quitSafely(); textureThread = null
        Log.i(TAG, "WebRTC SurfaceTexture subscriber stopped")
    }

    // -------------------------------------------------------------------------
    // 方式 B：MediaCodec InputSurface 方式
    // -------------------------------------------------------------------------

    private var encoder: MediaCodec? = null
    @Volatile private var draining = false
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private var subscriberIdB = -1

    private val callbackB = object : ICameraCallback.Stub() {
        override fun onFrameStart() = Unit
        override fun onError(code: Int, msg: String?) { Log.e(TAG, "camera error $code: $msg") }
        override fun onCameraClosed() = stopMediaCodec()
        override fun onConfigChanged(w: Int, h: Int) = Unit
    }

    /**
     * 方式 B 启动。
     *
     * @param onNalUnit  每个 H.264 NAL 单元的回调，在此处把数据投递给 WebRTC
     *                   的 EncodedImage / ExternalEncoder。
     *                   (presentationTimeUs, ByteArray, isKeyFrame)
     */
    fun startMediaCodec(
        width: Int,
        height: Int,
        bitrateBps: Int = 2_000_000,
        onNalUnit: (presentationUs: Long, data: ByteArray, isKeyFrame: Boolean) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        // 起 drain 线程，持续把编码输出传给 WebRTC
        drainThread = HandlerThread("webrtc-drain").apply { start() }
        drainHandler = Handler(drainThread!!.looper)
        draining = true
        drainHandler!!.post { drainLoop(enc, onNalUnit) }

        subscriberIdB = proxy.subscribe(inputSurface, callbackB)
        Log.i(TAG, "WebRTC MediaCodec subscriber started, id=$subscriberIdB")
    }

    fun stopMediaCodec() {
        draining = false
        val id = subscriberIdB.also { subscriberIdB = -1 }
        if (id > 0) runCatching { proxy.unsubscribe(id) }

        runCatching { encoder?.signalEndOfInputStream() }
        drainThread?.quitSafely(); drainThread = null

        runCatching { encoder?.stop(); encoder?.release() }
        encoder = null
        Log.i(TAG, "WebRTC MediaCodec subscriber stopped")
    }

    private fun drainLoop(
        enc: MediaCodec,
        onNalUnit: (Long, ByteArray, Boolean) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val index = enc.dequeueOutputBuffer(info, 10_000L)
            if (index < 0) continue

            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

            if (!isConfig && info.size > 0) {
                val buf = enc.getOutputBuffer(index)!!
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(data)
                // 把 NAL 单元交给 WebRTC 发送管线
                onNalUnit(info.presentationTimeUs, data, isKeyFrame)
            }

            enc.releaseOutputBuffer(index, false)
            if (isEos) break
        }
    }

    companion object {
        private const val TAG = "WebRtcSubscriber"
    }
}
