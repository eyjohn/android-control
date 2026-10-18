# PLAN.md — `android-control` Architecture & Implementation Plan

> **Status:** Implementation in Progress
> **Repository:** `/home/john/git/android-control`
> **Target Device:** Google Pixel 9a (Android 17, API 36; `minSdk = 30`)
> **Scope:** Pure `AccessibilityService` on-approval remote phone control over P2P **WebRTC DataChannel** with **Firebase RTDB + FCM (Google Account)** signaling. Zero `adbd` / Wireless Debugging dependency. Zero public host endpoints or Cloudflare tunnels required.

---

## 1. Executive Summary & Core Philosophy

`android-control` is a lightweight, zero-daemon Android companion app and Go host CLI that gives an AI agent (**Eva**) **on-approval remote control** of an Android phone over any network (5G cellular or Wi-Fi) **without Wireless Debugging, `adbd`, or custom public host tunnels**.

### Key Invariants
1. **Zero Wireless Debugging Notifications**: Relies 100% on Android's native `AccessibilityService` (`takeScreenshot()`, `rootInActiveWindow`, `dispatchGesture()`, `ACTION_SET_TEXT`, `performGlobalAction()`) + standard Android Intents. Wireless Debugging remains permanently **OFF**.
2. **Zero Host Endpoints & Zero Tunnels**: Signaling is handled via Google's edge (Firebase RTDB + FCM). Neither PC nor phone opens public server ports or needs Cloudflare tunnels.
3. **P2P WebRTC DataChannel**: After lightweight signaling (<10 KB), all RPC commands, screenshots, layout trees, and touch events flow directly peer-to-peer over an encrypted WebRTC DataChannel (Pion v4 on host, Stream WebRTC on Android).
4. **Strict On-Approval Lifecycle (0% Battery When Idle)**:
   - App does not run persistent background sockets or wake-locks when idle.
   - When the agent calls `android-ctl request`, an FCM high-priority push wakes the phone and triggers a native Android heads-up notification: **"Eva requests phone control [Approve] [Deny]"**.
   - User taps **Approve** on their phone (or inside the app). Phone logs in via Google Sign-In, writes the WebRTC Answer to Firebase RTDB, and connects the P2P DataChannel.
   - When the task finishes, TTL expires, or user taps **Disconnect**, WebRTC terminates immediately.
5. **Universal Pipe for Future Use Cases**: The WebRTC peer connection can multiplex multiple DataChannels (`control`, `clipboard`, `file`, `adb`, `media`) serving any phone $\leftrightarrow$ PC workflows.
6. **CLI Parity with `android-cli`**: Host CLI (`android-ctl`) mirrors the ergonomics of `android layout`, `android screen capture --annotate`, and `android screen resolve`.

---

## 2. End-to-End Architecture

```mermaid
sequenceDiagram
    autonumber
    participant Agent as Agent (Eva / CLI)
    participant Host as android-ctl (Host Pion WebRTC)
    participant FB as Firebase RTDB + FCM (Google Cloud)
    participant App as Android Control App (Pixel 9a)
    participant A11y as ControlAccessibilityService

    Agent->>Host: android-ctl request --reason "Verify grocery order" --ttl 10m
    Host->>Host: Generate WebRTC Offer + SessionID
    Host->>FB: Write Offer to /signaling/<session>/offer + trigger FCM push
    FB->>App: Native Heads-Up Push Notification: "Eva requests access [Approve (10m)] [Deny]"
    Note over App: User taps [Approve]
    App->>FB: Read Offer, generate SDP Answer, write to /signaling/<session>/answer
    FB->>Host: Host receives Answer via RTDB (<100ms)
    Note over Host,App: P2P WebRTC STUN Handshake (stun.l.google.com:19302)
    Host<=>>App: Direct Encrypted WebRTC DataChannel Connected!
    loop While Session Active
        Agent->>Host: android-ctl layout / screen / tap / swipe / text / key
        Host->>App: JSON-RPC 2.0 over WebRTC DataChannel
        App->>A11y: Execute Accessibility / Intent Action
        A11y-->>App: Result (UI JSON Tree / Annotated PNG / Ack)
        App-->>Host: JSON-RPC Response over DataChannel
        Host-->>Agent: CLI stdout / saved file
    end
    Agent->>Host: android-ctl disconnect (or User taps Disconnect / TTL expires)
    Host->>App: Close WebRTC DataChannel
    App->>App: Reset to idle (0% battery)
```

---

## 3. Repository Layout

```text
/home/john/git/android-control/
├── PLAN.md                         # This architecture specification
├── README.md                       # Quickstart, architecture, and build docs
├── AGENTS.md                       # Agent development rules & devcycle commands
├── Makefile                        # Unified devcycle: fmt, lint, test, build
├── cmd/
│   └── android-ctl/                # Go host CLI & WebRTC controller
│       └── main.go
├── pkg/
│   ├── protocol/                   # Shared JSON-RPC 2.0 types & node models
│   │   ├── rpc.go
│   │   └── rpc_test.go
│   ├── signaling/                  # Signaling providers (Firebase RTDB/FCM & local fallback)
│   │   ├── signaling.go
│   │   ├── firebase.go
│   │   ├── local.go
│   │   └── signaling_test.go
│   └── rtc/                        # Host WebRTC controller (Pion WebRTC v4)
│       ├── peer.go
│       └── peer_test.go
├── android/                        # Kotlin + Jetpack Compose Companion App
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts        # minSdk 30, targetSdk 36, Stream WebRTC, Firebase, Compose
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/xml/accessibility_service_config.xml
│           └── java/com/evdev/androidcontrol/
│               ├── MainActivity.kt
│               ├── model/
│               │   └── RpcModels.kt
│               ├── service/
│               │   ├── ControlAccessibilityService.kt
│               │   ├── LayoutExtractor.kt
│               │   ├── ScreenCapturer.kt
│               │   └── GestureExecutor.kt
│               ├── rtc/
│               │   └── WebRtcClient.kt
│               ├── signaling/
│               │   ├── FirebaseSignaling.kt
│               │   └── FcmService.kt
│               └── ui/
│                   ├── MainScreen.kt
│                   └── MainViewModel.kt
└── skill/                          # Antigravity skill definition (~/.gemini/config/skills/android-control)
    ├── SKILL.md
    └── scripts/
        └── android-ctl
```

---

## 4. Detailed Component Specifications

### 4.1 JSON-RPC 2.0 Protocol (over WebRTC DataChannel)

| Method | Params | Implementation on Android (`ControlAccessibilityService`) | Return Payload |
| :--- | :--- | :--- | :--- |
| `ping` | `{}` | Checks service readiness and active window | `{"ok": true, "a11y_enabled": true, "foreground_package": "com.whatsapp", "screen_width": 1080, "screen_height": 2400}` |
| `layout` | `{"flat": bool, "full": bool}` | Traverses `rootInActiveWindow`. Assigns 1-based `#index` (1..N) to interactive elements, returns text, bounds, clickable, editable, etc. | `{"package": "...", "elements": [ ... ]}` |
| `screen` | `{"annotate": bool, "quality": int, "max_width": int}` | Calls `takeScreenshot()` (API 30+). If `annotate=true`, draws high-contrast numbered badges (`#1`, `#2`, ...) on `Canvas` copy. Returns PNG base64 and coordinate map. | `{"image_base64": "...", "format": "png", "width": 1080, "height": 2400, "annotations": {"1": {"center": [540, 1200], "bounds": [...]}}}` |
| `tap` | `{"x": int, "y": int, "duration_ms": int?}` OR `{"node_index": int}` | `node.performAction(ACTION_CLICK)` fallback to `dispatchGesture()` at `center`. | `{"ok": true, "x": 540, "y": 1200}` |
| `swipe` | `{"x1": int, "y1": int, "x2": int, "y2": int, "duration_ms": int}` | Builds `Path` and calls `dispatchGesture()`. | `{"ok": true}` |
| `text` | `{"text": string, "clear": bool?, "submit": bool?, "node_index": int?}` | `ACTION_SET_TEXT` + optional `ACTION_IME_ENTER`. | `{"ok": true}` |
| `key` | `{"action": "BACK" \| "HOME" \| "RECENTS" \| "NOTIFICATIONS" \| "QUICK_SETTINGS" \| "LOCK"}` | Calls `performGlobalAction(GLOBAL_ACTION_*)`. | `{"ok": true}` |
| `app.launch` | `{"package": string?, "uri": string?}` | Launches app via `PackageManager` or Intent with `FLAG_ACTIVITY_NEW_TASK`. | `{"ok": true}` |
| `app.list` | `{"include_system": bool?}` | Queries launchable activities. | `{"packages": [{"package": "...", "label": "..."}]}` |
| `session.disconnect` | `{"reason": string?}` | Closes WebRTC DataChannel cleanly. | `{"ok": true}` |

---

## 5. Phased Implementation Roadmap

1. **Phase 1: Environment & Tooling Verification** (Completed: Go 1.22, OpenJDK 17, Android SDK linked, ADB verified).
2. **Phase 2: Go Host Core & Protocol** (`go.mod`, `pkg/protocol`, `pkg/signaling`, `pkg/rtc`, `cmd/android-ctl`).
3. **Phase 3: Android Companion App** (Gradle setup, `ControlAccessibilityService`, WebRTC DataChannel, Firebase RTDB/FCM, Compose UI).
4. **Phase 4: Unified Makefile, Tests, Devcycle & Skill Packaging**.
