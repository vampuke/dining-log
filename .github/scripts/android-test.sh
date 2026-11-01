#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
adb shell settings put secure show_ime_with_hard_keyboard 1
cd android
set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon
test_status=$?
mkdir -p app/build/outputs/p4-screenshots
adb pull /sdcard/Android/data/top.alanvampuke.dininglog/files/p4-screenshots app/build/outputs/p4-screenshots/
set -e
exit "$test_status"
