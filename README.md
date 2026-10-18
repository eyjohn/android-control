# android-control

Pure `AccessibilityService` remote phone control over P2P WebRTC DataChannel with automated Firebase RTDB/FCM signaling.

## Features
- **Zero Wireless Debugging**: 100% native Android `AccessibilityService` (`takeScreenshot()`, `rootInActiveWindow`, `dispatchGesture()`).
- **Zero Public Endpoints**: Automated Google Account / Firebase signaling without open ports or Cloudflare tunnels.
- **P2P WebRTC DataChannel**: Ultra-low latency, direct encrypted channel between host PC and Pixel 9a.
- **1-Tap Push Approval**: Agent requests access -> native Android heads-up notification with `[Approve]` action -> instant WebRTC handshake.
- **Ergonomic Host CLI**: `android-ctl layout`, `android-ctl screen --annotate`, `android-ctl tap`, `android-ctl swipe`, `android-ctl text`, `android-ctl key`.

## Quickstart

### Setup Host CLI (Python / uv)
```bash
uv sync
uv run android-ctl --help
```

### Build Android Companion App
```bash
make build-android
```

### Full Dev Cycle
```bash
make devcycle
```
