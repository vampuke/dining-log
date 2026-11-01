#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell ime list -s
adb shell settings get secure default_input_method
cd android
set +e
mkdir -p app/build/outputs/p4-screenshots
# UTP uninstalls test APKs at the end, removing app-owned external files.
# Copy screenshots while instrumentation is running, including failure evidence.
./gradlew :app:connectedDebugAndroidTest --no-daemon &
gradle_pid=$!
while kill -0 "$gradle_pid" 2>/dev/null; do
  adb pull /sdcard/Android/data/top.alanvampuke.dininglog/files/p4-screenshots app/build/outputs/p4-screenshots/ >/dev/null 2>&1
  sleep 1
done
wait "$gradle_pid"
test_status=$?
adb pull /sdcard/Android/data/top.alanvampuke.dininglog/files/p4-screenshots app/build/outputs/p4-screenshots/ >/dev/null 2>&1
adb logcat -d -s AndroidRuntime:E DiningP4:I System.out:I > app/build/outputs/p4-screenshots/android-runtime.log
set -e
exit "$test_status"
