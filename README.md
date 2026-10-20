# android-control

Pure `AccessibilityService` remote phone control over P2P WebRTC DataChannel with automated Firebase RTDB/FCM signaling.

## Features
- **Zero Wireless Debugging**: 100% native Android `AccessibilityService` (`takeScreenshot()`, `rootInActiveWindow`, `dispatchGesture()`).
- **Zero Public Endpoints**: Automated Google Account / Firebase signaling without open ports or Cloudflare tunnels.
- **P2P WebRTC DataChannel**: Ultra-low latency, direct encrypted channel between host PC and Pixel 11 Pro.
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

## Architecture & Security Model

### Authentication & Signaling
- **Host PC (Firebase App Auth)**: Host CLI exchanges Google Sign-In ID tokens via Firebase `accounts:signInWithIdp` (`android-ctl auth login`), persisting auto-refreshing credentials to `~/.config/android-control/auth.json`. REST calls to Firebase RTDB are authenticated via `?auth=<firebase_token>` matching the user's partition rules (`/users/$uid/`). Requires loopback redirect URI registration in GCP Console.
- **Android Companion App (Firebase Auth & Security Rules)**: The companion app requests a Google ID token (`audience:server:client_id:<WEB_CLIENT_ID>`), exchanges it via Firebase `accounts:signInWithIdp`, and uses the Firebase Auth ID token (`?auth=<firebase_token>`) evaluated against RTDB Security Rules (`auth.token.email === <user_email>`). Tokens auto-refresh when <5 minutes remain.
- **Offer Verification & Deduplication**: Unauthenticated deep-link injection is blocked. Offers are strictly verified against the signed-in Google account and deduplicated by `(session_id, sdp_hash, timestamp_ms)`. Local ADB fallback uses a protected `BroadcastReceiver` restricted to shell/root (`android.permission.DUMP`).

### Connection Lifecycle & Liveness
- **Persistent Session Ownership**: WebRTC peer connections and signaling loops are owned by a process-wide `SessionManager` bound to the active `ControlAccessibilityService`, keeping connections alive through screen rotations and backgrounding.
- **Ordered ADB Tunnel**: Multiplexed ADB DataChannel uses ordered channel writing and backpressure (>1 MB buffer threshold) to avoid packet drops or socket flapping.
- **Single Idle Authority**: Host daemon orchestrates session idle teardown (default 300s), while the phone enforces an idle backstop (600s) with teardown notices and periodic 10s liveness keepalives.

## Full Dev Cycle
```bash
make devcycle
```
