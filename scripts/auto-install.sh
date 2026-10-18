#!/usr/bin/env bash
set -euo pipefail

APK_PATH="/home/john/git/android-control/android/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "==> Building APK first..."
    cd /home/john/git/android-control/android && ./gradlew assembleDebug
fi

echo "==> Waiting for device on USB / ADB..."
echo "    (Ensure USB Debugging is ON in Developer Options)"
adb wait-for-device

echo "==> Device connected:"
adb devices -l

echo "==> Installing $APK_PATH..."
adb install -r "$APK_PATH"

echo "==> Installed successfully!"
echo "    Next steps on phone:"
echo "    1. Open Settings -> Accessibility -> Downloaded apps -> Android Control -> Turn ON"
echo "    (If Restricted Setting prompt appears: Settings -> Apps -> Android Control -> ⋮ -> Allow restricted settings)"
