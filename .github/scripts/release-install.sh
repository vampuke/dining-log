#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
apk="$GITHUB_WORKSPACE/android/app/build/outputs/apk/release/app-release.apk"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$api" = 26 || test "$api" = 35
runtime="$RUNNER_TEMP/dining-release-runtime-api-$api.txt"
# Each action starts a fresh AVD. Read its runtime log rather than clearing
# buffers: API 26 can reject logcat -c before installation has even started.
record_failed_runtime() {
  local status=$?
  trap - EXIT
  if test "$status" -ne 0 && ! test -f "$runtime"; then
    if ! adb logcat -d -s AndroidRuntime:E > "$runtime"; then
      printf 'Runtime diagnostics unavailable on API %s\n' "$api" >&2
    fi
  fi
  exit "$status"
}
trap record_failed_runtime EXIT
if test -f "$RUNNER_TEMP/dining-previous-release.apk"; then
  adb install "$RUNNER_TEMP/dining-previous-release.apk"
  adb install -r "$apk"
else
  adb install "$apk"
fi
adb shell am start -W -n top.alanvampuke.dininglog/top.alanvampuke.dininglog.MainActivity > "$RUNNER_TEMP/dining-release-launch-api-$api.txt"
grep -q '^Status: ok' "$RUNNER_TEMP/dining-release-launch-api-$api.txt"
sleep 3
test -n "$(adb shell pidof top.alanvampuke.dininglog | tr -d '\r')"
adb logcat -d -s AndroidRuntime:E > "$runtime"
if grep -q 'FATAL EXCEPTION' "$runtime"; then
  cat "$runtime"
  exit 1
fi
# A reinstall confirms the fixed certificate is accepted; an initial release
# still has no older released version for a real version-to-version upgrade.
adb install -r "$apk"
printf 'signed APK installed, launched and reinstalled on API %s\n' "$api"
