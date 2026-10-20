package com.evdev.androidcontrol.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.evdev.androidcontrol.model.*
import kotlinx.serialization.json.*

class ControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ControlAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // F1: Ensure SessionManager is initialized and bound to the persistent AccessibilityService lifecycle
        com.evdev.androidcontrol.session.SessionManager.getInstance(applicationContext).autoSelectBestMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for passive listening
    }

    override fun onInterrupt() {
        // No-op
    }

    suspend fun handleRpc(method: String, params: JsonObject?): JsonElement {
        val root = rootInActiveWindow
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        return when (method) {
            "ping" -> {
                buildJsonObject {
                    put("ok", true)
                    put("a11y_enabled", true)
                    put("foreground_package", root?.packageName?.toString() ?: "unknown")
                    put("screen_width", width)
                    put("screen_height", height)
                }
            }

            "layout" -> {
                val flat = params?.get("flat")?.jsonPrimitive?.booleanOrNull ?: true
                val full = params?.get("full")?.jsonPrimitive?.booleanOrNull ?: false
                val layoutRes = LayoutExtractor.extract(root, width, height, flat = flat, full = full)
                json.encodeToJsonElement(layoutRes)
            }

            "screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val annotate = params?.get("annotate")?.jsonPrimitive?.booleanOrNull ?: true
                    val quality = params?.get("quality")?.jsonPrimitive?.intOrNull ?: 80
                    val screenRes = ScreenCapturer.capture(this, annotate = annotate, quality = quality)
                    json.encodeToJsonElement(screenRes)
                } else {
                    buildJsonObject {
                        put("error", "takeScreenshot requires Android 11+ (API 30)")
                    }
                }
            }

            "tap" -> {
                val nodeIndex = params?.get("node_index")?.jsonPrimitive?.intOrNull
                val x = params?.get("x")?.jsonPrimitive?.floatOrNull
                val y = params?.get("y")?.jsonPrimitive?.floatOrNull
                val duration = params?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 50L

                val success = if (x != null && y != null) {
                    GestureExecutor.performTap(this, x, y, duration)
                } else if (nodeIndex != null && root != null) {
                    val layout = LayoutExtractor.extract(root, width, height)
                    val elem = layout.elements.find { it.index == nodeIndex }
                    if (elem != null && elem.center.size == 2) {
                        GestureExecutor.performTap(this, elem.center[0].toFloat(), elem.center[1].toFloat(), duration)
                    } else {
                        false
                    }
                } else {
                    false
                }
                buildJsonObject { put("ok", success) }
            }

            "swipe" -> {
                val x1 = params?.get("x1")?.jsonPrimitive?.floatOrNull ?: 0f
                val y1 = params?.get("y1")?.jsonPrimitive?.floatOrNull ?: 0f
                val x2 = params?.get("x2")?.jsonPrimitive?.floatOrNull ?: 0f
                val y2 = params?.get("y2")?.jsonPrimitive?.floatOrNull ?: 0f
                val duration = params?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 300L

                val success = GestureExecutor.performSwipe(this, x1, y1, x2, y2, duration)
                buildJsonObject { put("ok", success) }
            }

            "text" -> {
                val content = params?.get("text")?.jsonPrimitive?.content ?: ""
                val clear = params?.get("clear")?.jsonPrimitive?.booleanOrNull ?: false
                val submit = params?.get("submit")?.jsonPrimitive?.booleanOrNull ?: false
                val nodeIndex = params?.get("node_index")?.jsonPrimitive?.intOrNull

                val success = GestureExecutor.setText(root, content, clear, submit, nodeIndex)
                buildJsonObject { put("ok", success) }
            }

            "key" -> {
                val action = params?.get("action")?.jsonPrimitive?.content ?: "BACK"
                val success = GestureExecutor.performGlobal(this, action)
                buildJsonObject { put("ok", success) }
            }

            "app.launch" -> {
                val pkg = params?.get("package")?.jsonPrimitive?.content
                val uriStr = params?.get("uri")?.jsonPrimitive?.content

                val intent = if (!pkg.isNullOrBlank()) {
                    packageManager.getLaunchIntentForPackage(pkg)
                } else if (!uriStr.isNullOrBlank()) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
                } else {
                    null
                }

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    buildJsonObject { put("ok", true) }
                } else {
                    buildJsonObject { put("ok", false); put("error", "Package or URI not launchable") }
                }
            }

            else -> {
                buildJsonObject { put("error", "Unsupported RPC method: $method") }
            }
        }
    }
}

