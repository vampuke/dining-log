#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
apk="$GITHUB_WORKSPACE/android/app/build/outputs/apk/release/app-release.apk"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$api" = 26 || test "$api" = 35
adb logcat -c
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
adb logcat -d -s AndroidRuntime:E > "$RUNNER_TEMP/dining-release-runtime-api-$api.txt"
if grep -q 'FATAL EXCEPTION' "$RUNNER_TEMP/dining-release-runtime-api-$api.txt"; then
  cat "$RUNNER_TEMP/dining-release-runtime-api-$api.txt"
  exit 1
fi
# A reinstall confirms the fixed certificate is accepted; an initial release
# still has no older released version for a real version-to-version upgrade.
adb install -r "$apk"
printf 'signed APK installed, launched and reinstalled on API %s\n' "$api"
