package com.evdev.androidcontrol.model

enum class ControlMode {
    ACCESSIBILITY, // Primary (Zero-ADB AccessibilityService)
    ADB            // Alternative (WebRTC DataChannel -> 127.0.0.1:5555)
}
