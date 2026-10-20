package com.evdev.androidcontrol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: RpcError? = null
)

@Serializable
data class UiElement(
    val index: Int,
    val text: String? = null,
    val content_desc: String? = null,
    val resource_id: String? = null,
    val class_name: String? = null,
    val bounds: List<Int>, // [x1, y1, x2, y2]
    val center: List<Int>, // [cx, cy]
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
data class LayoutResponse(
    val package_name: String,
    val screen_width: Int,
    val screen_height: Int,
    val elements: List<UiElement>
)

@Serializable
data class AnnotationNode(
    val center: List<Int>,
    val bounds: List<Int>
)

@Serializable
data class ScreenResponse(
    val image_base64: String,
    val format: String = "png",
    val width: Int,
    val height: Int,
    val annotations: Map<String, AnnotationNode>
)

@Serializable
data class SessionOffer(
    val session_id: String,
    val sdp: String,
    val reason: String = "AI Agent Remote Control",
    val ttl_seconds: Int = 600,
    val timestamp_ms: Long = 0
)

@Serializable
data class SessionAnswer(
    val session_id: String,
    val sdp: String,
    val approved: Boolean = true,
    val device_name: String = "Pixel 11 Pro",
    val timestamp_ms: Long = 0
)

