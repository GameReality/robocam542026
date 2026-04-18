package com.robocam.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;

/**
 * One shared "frame slot" holds the latest JPEG + a sequence number.
 * Camera callback writes in and calls notifyAll(). Each HTTP streaming
 * thread calls waitForNextFrame() and blocks until a newer frame arrives.
 * Camera thread never blocks regardless of client count or speed.
 */
public class MjpegStreamer {

    private static final int WIDTH  = 640;
    private static final int HEIGHT = 480;

    private ImageReader   imageReader;
    private HandlerThread backgroundThread;
    private Handler       backgroundHandler;

    private final Object frameLock   = new Object();
    private byte[]       latestFrame = null;
    private int          frameSeq    = 0;
    private volatile boolean stopped = false;

    public static final class FrameResult {
        public final byte[] data;
        public final int    seq;
        FrameResult(byte[] data, int seq) { this.data = data; this.seq = seq; }
    }

    public MjpegStreamer(Context context) {
        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
        startBackgroundThread();
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpegBytes = new byte[buffer.remaining()];
                buffer.get(jpegBytes);
                synchronized (frameLock) {
                    latestFrame = jpegBytes;
                    frameSeq++;
                    frameLock.notifyAll();
                }
            } finally {
                image.close();
            }
        }, backgroundHandler);
    }

    public ImageReader getImageReader() {
        return imageReader;
    }

    /**
     * Blocks until a frame newer than lastSeq is available, timeout, or stop.
     * Pass lastSeq=0 to get the very next frame.
     */
    public FrameResult waitForNextFrame(int lastSeq, long timeoutMs)
            throws InterruptedException {
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!stopped && (latestFrame == null || frameSeq == lastSeq)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                frameLock.wait(remaining);
            }
            if (stopped) return null;
            return new FrameResult(latestFrame, frameSeq);
        }
    }

    public void stop() {
        stopped = true;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("MjpegStreamer");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }
}
