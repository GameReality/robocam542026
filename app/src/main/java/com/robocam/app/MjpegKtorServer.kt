package com.robocam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MjpegKtorServer(private val context: Context, private val port: Int = 8088) {

    private val _frameFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val frameFlow = _frameFlow.asSharedFlow()
    
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/cam") {
                    call.respondBytesWriter(contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")) {
                        frameFlow.collect { frame ->
                            writeStringUtf8("--frame\r\n")
                            writeStringUtf8("Content-Type: image/jpeg\r\n")
                            writeStringUtf8("Content-Length: ${frame.size}\r\n")
                            writeStringUtf8("\r\n")
                            writeFully(frame)
                            writeStringUtf8("\r\n")
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)
        Log.d("MjpegKtorServer", "Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        executor.shutdown()
    }

    fun getImageAnalysis(): ImageAnalysis {
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processImage(imageProxy)
        }
        return imageAnalysis
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val image = imageProxy.image ?: return
        
        // Convert YUV to JPEG
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val out = ByteArrayOutputStream()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
        
        val imageBytes = out.toByteArray()
        _frameFlow.tryEmit(imageBytes)
        
        imageProxy.close()
    }
}
