package com.example.cameraproxy;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.example.cameraproxy.ICameraCallback;
import com.example.cameraproxy.CameraCapabilities;
import com.example.cameraproxy.IPhotoCaptureCallback;

/**
 * Client -> Server 的主接口，用于控制共享相机。
 *
 * 注意：所有方法都是同步（非 oneway）的 Binder 调用，
 *       Client 调用时会阻塞直到 Server 返回。
 *       因此 Client 不要在主线程调用 openCamera / subscribe 等重方法。
 */
interface ICameraProxyService {
    /**
     * 幂等打开相机。
     * - 如果相机已经按相同配置打开，直接返回 true；
     * - 如果配置不同会先关再开（会有短暂黑屏）；
     * - 只有第一个订阅者需要调用，后续订阅者可以跳过（但调也安全）。
     *
     * @param cameraId  Camera2 API 的相机 ID（"0"、"1" ...）
     * @param width     期望分辨率宽
     * @param height    期望分辨率高
     * @param format    像素格式（ImageFormat.PRIVATE 等）
     * @return 调用结束后相机是否在运行
     */
    boolean openCamera(String cameraId, int width, int height, int format);

    /** 主动请求关闭相机 —— 仅当**没有任何订阅者**时才真正关闭，否则 no-op。 */
    void closeCamera();

    /**
     * 订阅一路相机流。
     *
     * @param surface   Client 提供的消费端 Surface（例如 SurfaceView.holder.surface）。
     *                  Server 会把每帧 GL 绘制结果直接 swapBuffers 到这个 Surface，
     *                  中间不经过字节数组拷贝 —— 这就是"零拷贝"的由来。
     * @param callback  事件回调，Server 通过它通知 Client 首帧 / 错误 / 关闭。
     * @return 订阅者 ID（>0），后续用于 unsubscribe / requestControl；
     *         失败返回 -1（比如相机没开、Surface 无效）。
     */
    int subscribe(in Surface surface, ICameraCallback callback);

    /**
     * 取消订阅。
     * - 传入未知 id 不会抛异常（安全幂等）；
     * - Server 释放对应的 EGLSurface 资源；
     * - 当最后一个订阅者退出时，相机自动关闭。
     */
    void unsubscribe(int subscriberId);

    /**
     * Request one still JPEG capture for an active subscriber.
     *
     * The client owns the destination and passes a writable file descriptor.
     * The service writes JPEG bytes to its duplicated descriptor and closes it
     * on every success/failure path. Large image data is intentionally not sent
     * through Binder.
     *
     * @return positive request ID when accepted; -1 when rejected before Camera2 work starts.
     */
    long capturePhoto(int subscriberId, in ParcelFileDescriptor output, IPhotoCaptureCallback callback);

    /**
     * 查询指定相机的能力（分辨率列表、朝向、方向等）。
     * 相机没打开也能查（Camera2 的 characteristics 是静态属性）。
     */
    CameraCapabilities getCapabilities(String cameraId);

    /**
     * 请求独占控制权（用于修改相机参数，如曝光）。
     * 同一时刻只有一个订阅者能持有控制权 —— CAS 保证互斥。
     * @return true=成功拿到；false=已被其他订阅者占用
     */
    boolean requestControl(int subscriberId);

    /**
     * 修改曝光参数。
     * 必须先通过 requestControl 获得控制权，否则 Server 会抛 SecurityException。
     */
    void setExposure(int subscriberId, int value);

    /** 释放控制权，让其他订阅者可以 requestControl。 */
    void releaseControl(int subscriberId);
}
