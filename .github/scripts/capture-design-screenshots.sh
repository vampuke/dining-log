#!/usr/bin/env bash
# Design capture only: run DesignCaptureTest on the emulator and copy the
# PNGs off the device while instrumentation is running (UTP uninstalls the
# test APKs at the end, removing app-owned external files). Same pattern as
# .github/scripts/android-test.sh.
#
# Kept as a real script file (called from design-capture.yml as a single
# line) because android-emulator-runner executes each line of its `script:`
# input in a fresh shell, so `cd`, variables and `set` flags do not survive
# across lines -- the inline version died with `./gradlew: not found`.
set -euo pipefail
cd android
mkdir -p app/build/outputs/p4-screenshots
set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=top.alanvampuke.dininglog.product.DesignCaptureTest &
gradle_pid=$!
while kill -0 "$gradle_pid" 2>/dev/null; do
  adb pull /sdcard/Android/data/top.alanvampuke.dininglog.debug/files/p4-screenshots app/build/outputs/p4-screenshots/ >/dev/null 2>&1 || true
  sleep 1
done
wait "$gradle_pid"
test_status=$?
adb pull /sdcard/Android/data/top.alanvampuke.dininglog.debug/files/p4-screenshots app/build/outputs/p4-screenshots/ >/dev/null 2>&1 || true
exit "$test_status"
