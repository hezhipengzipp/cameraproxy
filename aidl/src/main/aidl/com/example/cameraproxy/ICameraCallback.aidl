package com.example.cameraproxy;

interface ICameraCallback {
    oneway void onFrameStart();
    oneway void onError(int code, String msg);
    oneway void onCameraClosed();
    oneway void onConfigChanged(int width, int height);
}
