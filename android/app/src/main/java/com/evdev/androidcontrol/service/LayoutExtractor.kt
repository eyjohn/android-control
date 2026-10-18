package com.evdev.androidcontrol.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.evdev.androidcontrol.model.LayoutResponse
import com.evdev.androidcontrol.model.UiElement

object LayoutExtractor {

    fun extract(
        rootNode: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        flat: Boolean = true,
        full: Boolean = false
    ): LayoutResponse {
        if (rootNode == null) {
            return LayoutResponse(
                package_name = "unknown",
                screen_width = screenWidth,
                screen_height = screenHeight,
                elements = emptyList()
            )
        }

        val pkg = rootNode.packageName?.toString() ?: "unknown"
        val elements = mutableListOf<UiElement>()
        val boundsRect = Rect()
        var currentIndex = 1

        fun traverse(node: AccessibilityNodeInfo) {
            node.getBoundsInScreen(boundsRect)

            val isInteractive = node.isClickable || node.isEditable || node.isScrollable ||
                    node.isCheckable || node.isLongClickable

            val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val hasArea = boundsRect.width() > 0 && boundsRect.height() > 0

            val shouldInclude = full || (hasArea && (isInteractive || hasContent))

            if (shouldInclude) {
                val cx = boundsRect.centerX()
                val cy = boundsRect.centerY()

                elements.add(
                    UiElement(
                        index = currentIndex++,
                        text = node.text?.toString(),
                        content_desc = node.contentDescription?.toString(),
                        resource_id = node.viewIdResourceName,
                        class_name = node.className?.toString(),
                        bounds = listOf(boundsRect.left, boundsRect.top, boundsRect.right, boundsRect.bottom),
                        center = listOf(cx, cy),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        focused = node.isFocused,
                        enabled = node.isEnabled
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
            }
        }

        traverse(rootNode)

        return LayoutResponse(
            package_name = pkg,
            screen_width = screenWidth,
            screen_height = screenHeight,
            elements = elements
        )
    }
}

