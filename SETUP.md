# Setup Guide — Google Cloud, Firebase & `android-control`

This guide explains how to set up the necessary Google Cloud Platform (GCP) and Firebase infrastructure to run `android-control` with user-partitioned authentication and device discovery across the Android companion app and host CLI.

---

## 1. Architecture & Security Invariants

1. **Signaling Mailbox**: Firebase Realtime Database (RTDB) acts solely as a rendezvous mailbox (<10 KB payload). Once WebRTC SDP Offer/Answer is exchanged, all control, screenshots, gestures, and ADB tunnels flow direct peer-to-peer over an encrypted WebRTC DataChannel.
2. **User Partitioning (`/users/$uid/`)**: Device registrations, inboxes, and session offers/answers are partitioned by Firebase User ID (`auth.uid`), guaranteeing multi-tenant isolation without hardcoded email rules.
3. **Host Auth Model**:
   - Host runs `android-ctl auth login` -> browser Google Sign-In -> Firebase Auth tokens stored in `~/.config/android-control/auth.json` with perpetual background refresh. Evaluated directly against RTDB security rules without requiring GCP project IAM permissions or `gcloud`.

---

## 2. Configurable Identifiers & Parameters

The app and CLI use three primary Firebase identifiers:

| Identifier | Description | Default / Example Value |
| :--- | :--- | :--- |
| `FIREBASE_DB_URL` | Firebase RTDB REST instance URL | `https://<your-project>-default-rtdb.firebaseio.com` |
| `FIREBASE_API_KEY` | Firebase Web API Key for auth exchange | `<your-firebase-web-api-key>` |
| `FIREBASE_WEB_CLIENT_ID` | OAuth 2.0 Web Client ID for Google ID Token | `<your-web-client-id>.apps.googleusercontent.com` |
| `TURN_SERVER` | WebRTC STUN/TURN server URLs | `turn:<turn-server-ip>:3478?transport=udp,...` |

### How to Tune Parameters

#### On Host CLI (Python)
Parameters can be tuned via environment variables or configuration files:
- **Environment variables**:
  ```bash
  export FIREBASE_DATABASE_URL="https://<your-project>-default-rtdb.firebaseio.com"
  export FIREBASE_API_KEY="AIzaSy..."
  export FIREBASE_WEB_CLIENT_ID="<client-id>.apps.googleusercontent.com"
  ```
- **Local `.env` or `~/.config/android-control/.env`**:
  ```env
  FIREBASE_DATABASE_URL=https://<your-project>-default-rtdb.firebaseio.com
  FIREBASE_API_KEY=AIzaSy...
  FIREBASE_WEB_CLIENT_ID=<client-id>.apps.googleusercontent.com
  ```
- **Local `turn.json` or `~/.config/android-control/turn.json`**:
  ```json
  {
    "urls": ["turn:turn.yourdomain.com:3478?transport=udp"],
    "username": "turnuser",
    "credential": "secretpassword"
  }
  ```

#### On Android Companion App
Parameters can be tuned at build-time or runtime:
- **Gradle Build Properties** (`android/gradle.properties` or CLI):
  ```bash
  ./gradlew assembleDebug \
    -PFIREBASE_DB_URL="https://<your-project>-default-rtdb.firebaseio.com" \
    -PFIREBASE_API_KEY="AIzaSy..." \
    -PFIREBASE_WEB_CLIENT_ID="<client-id>.apps.googleusercontent.com"
  ```
- **Environment variables during build**:
  ```bash
  export ANDROID_CONTROL_FIREBASE_DB_URL="https://<your-project>-default-rtdb.firebaseio.com"
  export ANDROID_CONTROL_FIREBASE_API_KEY="AIzaSy..."
  export ANDROID_CONTROL_FIREBASE_WEB_CLIENT_ID="<client-id>.apps.googleusercontent.com"
  ./gradlew assembleDebug
  ```
- **Runtime Override (via adb shell or SharedPreferences `firebase_config`)**:
  ```bash
  # Target SharedPreferences file: /data/data/com.evdev.androidcontrol/shared_prefs/firebase_config.xml
  ```

---

## 3. Step-by-Step GCP & Firebase Setup

### Step 1: Create or Select GCP / Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/) and click **Add project** (or select existing GCP project).
2. Note your **Project ID** (e.g. `your-project-id`).

### Step 2: Enable Firebase Realtime Database
1. In Firebase Console, navigate to **Build** -> **Realtime Database** -> **Create Database**.
2. Select your preferred database location (e.g. `us-central1` or `europe-west1`).
3. Under the **Rules** tab, apply the user-partitioned security rules:
   ```json
   {
     "rules": {
       "users": {
         "$uid": {
           ".read": "auth != null && auth.uid === $uid",
           ".write": "auth != null && auth.uid === $uid"
         }
       },
       "android_control": {
         ".read": "auth != null",
         ".write": "auth != null"
       }
     }
   }
   ```
4. Note your database URL: `https://<project-id>-default-rtdb.firebaseio.com`.

### Step 3: Enable Firebase Authentication (Google Sign-In)
1. In Firebase Console, navigate to **Build** -> **Authentication** -> **Sign-in method**.
2. Click **Google** -> **Enable**.
3. Set **Project support email**.
4. Save changes. Under **Project Settings** (gear icon) -> **General**, note the **Web API Key** (`AIzaSy...`).

### Step 4: Register Android App & SHA-1 Fingerprint
1. In **Project Settings** -> **General** -> **Your apps**, click **Add app** (Android icon).
2. Set **Android package name**: `com.evdev.androidcontrol`.
3. Generate and paste the SHA-1 signing fingerprint:
   - For repository debug build:
     ```bash
     keytool -list -v -keystore android/app/debug.keystore -alias androiddebugkey -storepass android | grep "SHA1:"
     ```
4. Complete registration. (The generated Google Web Client ID is automatically provisioned under Google Cloud Credentials).

### Step 5: Configure OAuth Redirect URIs for Host CLI (CRITICAL)
Google OAuth strictly rejects authorization requests unless the loopback redirect URI is pre-registered on the Web Client ID:
1. Open [Google Cloud Console Credentials](https://console.cloud.google.com/apis/credentials).
2. Under **OAuth 2.0 Client IDs**, click the Web client matching `FIREBASE_WEB_CLIENT_ID` (e.g. `Web client (auto created by Google Service)`).
3. Under **Authorized redirect URIs**, add both of the following entries:
   ```
   http://127.0.0.1:8085/oauth2callback
   http://localhost:8085/oauth2callback
   ```
4. Click **Save** (Google may take ~1–2 minutes to propagate changes).
   > **Note**: Omitting this step causes `Error 400: redirect_uri_mismatch` when running `android-ctl auth login`.
   >
   > *(Alternative)*: If you do not want to pin port `8085`, create an OAuth client with type **Desktop application** in GCP Console. Google permits arbitrary loopback ports (`http://127.0.0.1:<port>`) for Desktop clients without explicit redirect URI registration.

### Step 6: (Optional) WebRTC TURN Relay Server
If connecting across restrictive mobile NATs or firewalls:
1. Deploy CoTURN on a cloud VM (e.g. Google Compute Engine).
2. Allow ingress UDP/TCP on port `3478` and UDP `49152-65535`.
3. Configure `turn.json` locally or set `TURN_SERVER`, `TURN_USERNAME`, and `TURN_PASSWORD`.

---

## 4. End-to-End Operational Workflow

### 1. Authenticate Host CLI
Check auth status:
```bash
android-ctl auth status
```
Log in using Google OAuth / Firebase Client Model:
```bash
android-ctl auth login
```

### 2. Device Discovery & Selection
Discover devices registered under your Google account:
```bash
android-ctl devices
# or
android-ctl devices list
```
Output:
```
                           Registered Android Devices                           
┏━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━┳━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━━┓
┃ Device ID     ┃ Device Name  ┃ Account       ┃ Mode ┃ Last Seen ┃ Status     ┃
┡━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━╇━━━━━━╇━━━━━━━━━━━╇━━━━━━━━━━━━┩
│ 204b88b23095… │ Google Pixel │ user@gmail.…  │ adb  │ 2m ago    │ ● Default  │
│               │ 11 Pro       │               │      │           │ (Latest)   │
└───────────────┴──────────────┴───────────────┴──────┴───────────┴────────────┘
```

Set a persistent default device:
```bash
android-ctl devices select 204b88b23095e63b
```

Clear device preference (reverts to latest checked-in device):
```bash
android-ctl devices clear
```

### 3. Connect Phone Control Session
Connect to default / selected device:
```bash
android-ctl request
```
Or target a specific device on demand:
```bash
android-ctl request --device 204b88b23095e63b
```
Maintain an active WebRTC ADB proxy tunnel:
```bash
android-ctl adb proxy [-d <device_id>]
```
