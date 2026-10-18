# AGENTS.md — Development Guidelines for `android-control`

## Project Overview
`android-control` provides zero-daemon, pure `AccessibilityService` remote control for Android devices (Pixel 9a) over an encrypted P2P WebRTC DataChannel with automated Firebase RTDB/FCM signaling.

## Architecture Invariants
1. **Zero Wireless Debugging**: Strictly no `adbd` over Wi-Fi / notifications. Uses native `AccessibilityService`.
2. **Zero Host Endpoints**: No public ports or Cloudflare tunnels required; signaling routed via Google Cloud (Firebase).
3. **P2P WebRTC**: High-throughput DataChannel for JSON-RPC 2.0 messages, annotations, and gestures.

## Dev Cycle & Commands
Run dev cycle after atomic code edits:
```bash
make devcycle
```
Sub-targets:
- `make fmt`: `uv run ruff format .` and Android formatting.
- `make lint`: `uv run ruff check .` and `uv run mypy src` and Android lint.
- `make test`: `uv run pytest` and `./gradlew testDebugUnitTest`.
- `make build-host`: Verify Python entrypoints and imports.
- `make build-android`: Build debug APK (`android/app/build/outputs/apk/debug/app-debug.apk`).
