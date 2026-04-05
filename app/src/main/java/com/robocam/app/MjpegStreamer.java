package com.robocam.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MjpegStreamer {
    private static final String TAG = "MjpegStreamer";
    private final Context context;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private final CopyOnWriteArrayList<PipedOutputStream> outputStreams = new CopyOnWriteArrayList<>();

    public MjpegStreamer(Context context) {
        this.context = context;
        // Create ImageReader with 640x480 format JPEG
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
        startBackgroundThread();
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpegBytes = new byte[buffer.remaining()];
                buffer.get(jpegBytes);
                image.close();
                writeFrame(jpegBytes);
            }
        }, backgroundHandler);
    }

    public ImageReader getImageReader() {
        return imageReader;
    }

    public void addOutputStream(PipedOutputStream stream) {
        outputStreams.add(stream);
    }

    public void removeOutputStream(PipedOutputStream stream) {
        outputStreams.remove(stream);
        try { stream.close(); } catch (IOException ignored) {}
    }

    public void start() {
    }

    public void stop() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("MjpegStreamer");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while stopping background thread", e);
            }
        }
    }

    private void writeFrame(byte[] jpegBytes) {
        if (outputStreams.isEmpty()) return;

        String header = "--frame\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpegBytes.length + "\r\n\r\n";
        byte[] headerBytes;
        try {
            headerBytes = header.getBytes("UTF-8");
        } catch (Exception e) {
            return;
        }

        for (PipedOutputStream out : outputStreams) {
            try {
                out.write(headerBytes);
                out.write(jpegBytes);
                out.write("\r\n".getBytes("UTF-8"));
                out.flush();
            } catch (IOException e) {
                // Client disconnected - remove this stream
                removeOutputStream(out);
            }
        }
    }
}
