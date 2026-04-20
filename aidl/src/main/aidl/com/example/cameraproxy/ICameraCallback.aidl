package com.example.cameraproxy;

/**
 * Server -> Client 的回调接口。
 *
 * 由 Client 实现（继承 ICameraCallback.Stub），在 subscribe() 时传给 Server。
 * Server 通过它把相机生命周期事件反向通知给每一个订阅者。
 *
 * 所有方法都标 oneway：
 *  - oneway = 异步调用，调用方不等对端返回，立即返回。
 *  - 这里必须用 oneway，否则 Server 在 GL 线程回调时被某个卡住的客户端阻塞，
 *    会拖垮整条渲染管线，其他订阅者一起黑屏。
 */
interface ICameraCallback {
    /** 首帧已经画到订阅者的 Surface 上，可以在 UI 上提示"预览开始"。 */
    oneway void onFrameStart();

    /**
     * 引擎层发生错误。
     * @param code 错误码（见 CameraEngine.ERR_* 常量）
     * @param msg  人类可读的错误信息
     */
    oneway void onError(int code, String msg);

    /** 摄像头被 HAL 主动断开（例如系统相机 App 抢占），所有订阅者都会收到。 */
    oneway void onCameraClosed();

    /** 输出分辨率发生变化，Client 可据此调整 SurfaceView 比例。 */
    oneway void onConfigChanged(int width, int height);
}
