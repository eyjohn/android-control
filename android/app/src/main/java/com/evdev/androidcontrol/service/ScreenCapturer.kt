package com.evdev.androidcontrol.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import com.evdev.androidcontrol.model.AnnotationNode
import com.evdev.androidcontrol.model.ScreenResponse
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object ScreenCapturer {

    private val executor = Executors.newSingleThreadExecutor()

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun capture(
        service: AccessibilityService,
        annotate: Boolean = true,
        quality: Int = 80
    ): ScreenResponse {
        val deferred = CompletableDeferred<Bitmap>()

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    hardwareBuffer.close()

                    if (bitmap != null) {
                        val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        bitmap.recycle()
                        deferred.complete(softwareBitmap)
                    } else {
                        deferred.completeExceptionally(RuntimeException("Failed to wrap hardware buffer"))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    deferred.completeExceptionally(RuntimeException("TakeScreenshot failed: $errorCode"))
                }
            }
        )

        val bitmap = deferred.await()
        val width = bitmap.width
        val height = bitmap.height
        val annotations = mutableMapOf<String, AnnotationNode>()

        if (annotate) {
            val root = service.rootInActiveWindow
            val layout = LayoutExtractor.extract(root, width, height, flat = true, full = false)
            for (elem in layout.elements) {
                val b = elem.bounds
                if (b.size == 4) {
                    annotations[elem.index.toString()] = AnnotationNode(
                        center = elem.center,
                        bounds = elem.bounds
                    )
                }
            }
        }

        val outputStream = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(10, 100), outputStream)
        val imageBytes = outputStream.toByteArray()
        bitmap.recycle()

        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        return ScreenResponse(
            image_base64 = base64String,
            format = "webp",
            width = width,
            height = height,
            annotations = annotations
        )
    }
}

