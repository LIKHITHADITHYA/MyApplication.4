package com.example.myapplication.core

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class FrameAnalyzer(private val listener: (imageProxy: ImageProxy) -> Unit) : ImageAnalysis.Analyzer {

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // Here, you would typically get the image from imageProxy.image
        // For example: val mediaImage = imageProxy.image
        // And then pass it to your processing library (OpenCV, ML Kit, etc.)

        // For now, we're just logging basic info and calling the listener,
        // which will then close the imageProxy.
        // The listener pattern allows for more complex processing to be injected
        // or for the processed result to be passed back.

        Log.d("FrameAnalyzer", "Frame received: ${imageProxy.width}x${imageProxy.height}, Rotation: ${imageProxy.imageInfo.rotationDegrees}, Timestamp: ${imageProxy.imageInfo.timestamp}")

        // Call the listener, which in our current DashboardFragment setup will close the imageProxy.
        // Alternatively, you could close imageProxy directly here if no further action is needed by the caller.
        listener(imageProxy)

        // IMPORTANT: If you don't pass imageProxy to a listener that closes it,
        // you MUST call imageProxy.close() here to avoid stalling the camera.
        // e.g., if the listener was not used:
        // imageProxy.close()
    }
}