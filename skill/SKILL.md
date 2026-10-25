---
name: android-control
description: Pure AccessibilityService remote control of Android device over P2P WebRTC DataChannel with automated Firebase RTDB signaling.
---

# Android Control Skill

Use this skill when you need remote control of an Android phone (inspecting UI layouts, capturing screenshots, tapping, swiping, typing text, launching apps) without enabling Wireless Debugging or adbd.

## Colocated Runner

Execute commands using the skill runner:
```bash
scripts/android-ctl <subcommand>
# or globally:
uv run --project /home/john/git/android-control android-ctl <subcommand>
```

## Common Recipes

### 1. Request Phone Session
```bash
scripts/android-ctl request --reason "Verify grocery order" --ttl 10m
```
*Dispatches WebRTC offer via Firebase RTDB to device. Device auto-approves via matching Google Account and handshakes P2P WebRTC DataChannel.*

### 2. Check Connection Status
```bash
scripts/android-ctl status
```

### 3. Capture Screen & Annotations
```bash
scripts/android-ctl screen --annotate -o _scratch/screen.png
```
*Saves screenshot PNG and numbered element map at `_scratch/screen.png.map.json`.*

### 4. Resolve Target & Tap
```bash
# Resolve #14 to coordinates
scripts/android-ctl resolve --screen _scratch/screen.png --string "tap #14"

# Or tap node index directly
scripts/android-ctl tap --node 14

# Or tap specific coordinates
scripts/android-ctl tap 540 1200
```

### 5. Type Text & Press Enter
```bash
scripts/android-ctl text "Hello World" --submit
```

### 6. System Keys (Back / Home / Recents)
```bash
scripts/android-ctl key BACK
scripts/android-ctl key HOME
scripts/android-ctl key RECENTS
```

### 7. Launch App
```bash
scripts/android-ctl launch com.whatsapp
```

### 8. Terminate Session
```bash
scripts/android-ctl disconnect
```

