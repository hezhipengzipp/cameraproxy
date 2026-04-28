package com.example.cameraproxy;

/**
 * Server -> Client callback for one asynchronous still photo request.
 *
 * Each callback carries the request ID returned by capturePhoto(), so clients can
 * correlate completion with their local output URI/file.
 */
interface IPhotoCaptureCallback {
    /** JPEG bytes were written to the output descriptor supplied for this request. */
    oneway void onPhotoCaptureSucceeded(long requestId);

    /** The request failed; the service has closed its copy of the output descriptor. */
    oneway void onPhotoCaptureFailed(long requestId, String msg);
}
