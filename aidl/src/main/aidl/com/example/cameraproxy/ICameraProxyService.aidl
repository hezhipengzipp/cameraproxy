package com.example.cameraproxy;

import android.view.Surface;
import com.example.cameraproxy.ICameraCallback;
import com.example.cameraproxy.CameraCapabilities;

interface ICameraProxyService {
    /** Idempotent open. Returns true if camera is running after the call. */
    boolean openCamera(String cameraId, int width, int height, int format);

    /** Close camera if no subscribers remain. */
    void closeCamera();

    /** Subscribe a consumer Surface. Returns an id, or -1 on failure. */
    int subscribe(in Surface surface, ICameraCallback callback);

    /** Cancel a subscription. Safe to call with an unknown id. */
    void unsubscribe(int subscriberId);

    /** Available sizes / formats for the given cameraId. */
    CameraCapabilities getCapabilities(String cameraId);

    /** Attempt to take exclusive control of camera parameters. */
    boolean requestControl(int subscriberId);

    /** Only the control owner may call this. */
    void setExposure(int subscriberId, int value);

    /** Release control so another subscriber can take it. */
    void releaseControl(int subscriberId);
}
