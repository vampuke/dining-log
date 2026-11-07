#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
cd "$GITHUB_WORKSPACE/android"
probe_output="$PWD/app/build/outputs/pr18-regression-probe"
mkdir -p "$probe_output/device-evidence"

collect_device_evidence() {
  # UTP uninstalls the APKs on completion; retain real measurements and failure
  # screenshots while instrumentation is still running, as in android-test.sh.
  adb pull /sdcard/Android/data/top.alanvampuke.dininglog.debug/files/p4-screenshots \
    "$probe_output/device-evidence/" >/dev/null 2>&1 || true
}

collect_final_diagnostics() {
  collect_device_evidence
  adb logcat -d -v threadtime > "$probe_output/logcat.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$probe_output/activity.txt" 2>&1 || true
  adb shell getprop > "$probe_output/device-properties.txt" 2>&1 || true
}
trap collect_final_diagnostics EXIT

adb logcat -c
adb shell settings get system font_scale > "$probe_output/initial-font-scale.txt"
# Runner 1.6.2 RunnerArgs parses "class" and TestRequestBuilder includes that
# exact class. No package filter, log-only mode, full suite or extra Gradle task.
set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=top.alanvampuke.dininglog.product.Pr18ReviewRegressionTest \
  > "$probe_output/gradle.log" 2>&1 &
probe_gradle_pid=$!
while kill -0 "$probe_gradle_pid" 2>/dev/null; do
  collect_device_evidence
  sleep 1
done
wait "$probe_gradle_pid"
probe_test_status=$?
set -e
printf '%s\n' "$probe_test_status" > "$probe_output/gradle-exit-status.txt"
cat "$probe_output/gradle.log"
exit "$probe_test_status"
