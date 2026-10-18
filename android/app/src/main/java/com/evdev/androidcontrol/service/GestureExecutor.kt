package com.evdev.androidcontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

object GestureExecutor {

    suspend fun performTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 50L
    ): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        return deferred.await()
    }

    suspend fun performSwipe(
        service: AccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        return deferred.await()
    }

    fun setText(
        rootNode: AccessibilityNodeInfo?,
        text: String,
        clear: Boolean = false,
        submit: Boolean = false,
        nodeIndex: Int? = null
    ): Boolean {
        if (rootNode == null) return false

        val target = if (nodeIndex != null) {
            findNodeByIndex(rootNode, nodeIndex)
        } else {
            rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: return false

        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (clear) "" else text
            )
        }

        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (submit) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        return success
    }

    fun performGlobal(service: AccessibilityService, actionName: String): Boolean {
        val action = when (actionName.uppercase()) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return service.performGlobalAction(action)
    }

    private fun findNodeByIndex(root: AccessibilityNodeInfo, targetIndex: Int): AccessibilityNodeInfo? {
        var count = 1
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val isInteractive = node.isClickable || node.isEditable || node.isScrollable
            if (isInteractive) {
                if (count == targetIndex) return node
                count++
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child)
                if (found != null) return found
            }
            return null
        }
        return search(root)
    }
}

