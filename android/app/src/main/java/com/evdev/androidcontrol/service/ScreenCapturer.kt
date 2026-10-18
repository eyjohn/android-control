package com.evdev.androidcontrol.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

            val canvas = Canvas(bitmap)
            val boxPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 32f
                style = Paint.Style.FILL
                isFakeBoldText = true
            }
            val badgeBgPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            for (elem in layout.elements) {
                val b = elem.bounds
                if (b.size == 4) {
                    val rect = Rect(b[0], b[1], b[2], b[3])
                    canvas.drawRect(rect, boxPaint)

                    val label = "#${elem.index}"
                    val textBounds = Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)

                    val badgeRect = Rect(
                        b[0],
                        b[1] - textBounds.height() - 10,
                        b[0] + textBounds.width() + 16,
                        b[1]
                    )
                    canvas.drawRect(badgeRect, badgeBgPaint)
                    canvas.drawText(label, (b[0] + 8).toFloat(), (b[1] - 6).toFloat(), textPaint)

                    annotations[elem.index.toString()] = AnnotationNode(
                        center = elem.center,
                        bounds = elem.bounds
                    )
                }
            }
        }

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, quality, outputStream)
        val imageBytes = outputStream.toByteArray()
        bitmap.recycle()

        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        return ScreenResponse(
            image_base64 = base64String,
            format = "png",
            width = width,
            height = height,
            annotations = annotations
        )
    }
}

