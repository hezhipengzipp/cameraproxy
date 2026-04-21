package com.example.cameraproxy.client

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.cameraproxy.ICameraCallback
import com.example.cameraproxy.ICameraProxyService
import java.io.FileDescriptor

/**
 * DVR 录制示例：用 MediaCodec.createInputSurface() 订阅相机代理。
 *
 * 核心思路：把编码器的 InputSurface 当作 Surface 传给 proxy.subscribe()，
 * CameraEngine GL 线程会直接把相机帧渲染进这个 Surface，MediaCodec 硬件
 * 编码器拿到帧后输出 H.264 NAL，再由 MediaMuxer 封装成 .mp4 文件。
 *
 * 整条链路 GPU → 编码器，不经过 CPU，不做任何 memcpy。
 */
class DvrSubscriberExample(private val proxy: ICameraProxyService) {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1

    @Volatile private var muxerStarted = false
    @Volatile private var draining = false

    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null

    private var subscriberId = -1

    private val callback = object : ICameraCallback.Stub() {
        override fun onFrameStart() = Unit
        override fun onError(code: Int, msg: String?) { Log.e(TAG, "camera error $code: $msg") }
        override fun onCameraClosed() = stop()
        override fun onConfigChanged(w: Int, h: Int) = Unit
    }

    /**
     * 开始录制。
     *
     * @param outputPath  输出 .mp4 文件路径
     * @param width       视频宽度（与 openCamera 时保持一致）
     * @param height      视频高度
     * @param bitrateBps  目标码率，默认 4 Mbps
     */
    /**
     * 录制到文件路径（Android 9 及以下，或写到 App 私有目录时使用）。
     */
    fun start(outputPath: String, width: Int, height: Int, bitrateBps: Int = 4_000_000) {
        startInternal(
            MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            width, height, bitrateBps
        )
    }

    /**
     * 录制到 FileDescriptor（Android 10+ MediaStore 场景）。
     * 调用方负责在 stop() 之后关闭 pfd 并把 IS_PENDING 置 0。
     */
    fun start(fd: FileDescriptor, width: Int, height: Int, bitrateBps: Int = 4_000_000) {
        startInternal(
            MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            width, height, bitrateBps
        )
    }

    private fun startInternal(muxerInstance: MediaMuxer, width: Int, height: Int, bitrateBps: Int) {
        // 1. 配置 H.264 编码器
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) // Surface 输入模式
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 每秒一个 IDR 帧
        }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // 2. 拿到编码器的 InputSurface —— 这就是要传给 proxy 的 Surface
        val inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        // 3. 初始化 Muxer（此时先不 start，等拿到 SPS/PPS 后再 start）
        muxer = muxerInstance

        // 4. 起一条线程持续把编码器输出写进 Muxer
        drainThread = HandlerThread("dvr-drain").apply { start() }
        drainHandler = Handler(drainThread!!.looper)
        draining = true
        drainHandler!!.post { drainLoop() }

        // 5. 把 InputSurface 传给 proxy —— 从此 GL 线程直接喂帧给编码器
        subscriberId = proxy.subscribe(inputSurface, callback)
        Log.i(TAG, "DVR started, subscriberId=$subscriberId")
    }

    fun stop() {
        draining = false

        val id = subscriberId.also { subscriberId = -1 }
        if (id > 0) runCatching { proxy.unsubscribe(id) }

        // 通知编码器输入结束，让它把剩余帧都编出来
        runCatching { encoder?.signalEndOfInputStream() }

        drainThread?.quitSafely()
        drainThread = null

        runCatching { encoder?.stop(); encoder?.release() }
        encoder = null

        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false

        Log.i(TAG, "DVR stopped")
    }

    /**
     * 持续轮询编码器输出缓冲区，把 NAL 写进 Muxer。
     * 跑在独立线程，不阻塞调用方。
     */
    private fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val index = enc.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 第一次拿到 SPS/PPS 时才能 start Muxer
                    videoTrackIndex = muxer!!.addTrack(enc.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (muxerStarted && info.size > 0) {
                        val buf = enc.getOutputBuffer(index)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer!!.writeSampleData(videoTrackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (isEos) break
                }
            }
        }
    }

    companion object {
        private const val TAG = "DvrSubscriber"
        private const val DRAIN_TIMEOUT_US = 10_000L // 10ms
    }
}
