#!/bin/bash
# 从指定 run 下载 app-debug artifact 并发布
set -u
RUN=${1:?run id required}
TOKEN=$(grep -o 'https://[^@]*@github.com' ~/.git-credentials | head -1 | sed 's|https://||; s|@github.com||' | cut -d: -f2)
cd /tmp || exit 1
rm -rf apkdl && mkdir apkdl && cd apkdl || exit 1

ART=$(curl -sS -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/vampuke/dining-log/actions/runs/$RUN/artifacts" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d.get('artifacts',[]):
    if a['name']=='app-debug': print(a['id'])
")
echo "artifact id: $ART"
[ -z "$ART" ] && { echo "NO_ARTIFACT"; exit 1; }

curl -sSL -H "Authorization: token $TOKEN" -o app.zip \
  "https://api.github.com/repos/vampuke/dining-log/actions/artifacts/$ART/zip"
echo "downloaded $(stat -c%s app.zip) bytes"
unzip -o -q app.zip || { echo "UNZIP_FAIL"; exit 1; }
find . -name '*.apk' -not -name 'app.zip'

APK=$(find . -name '*.apk' | head -1)
[ -z "$APK" ] && { echo "NO_APK_IN_ZIP"; exit 1; }
echo "apk: $APK  $(stat -c%s "$APK") bytes"
sha256sum "$APK"
md5sum "$APK"
echo "--- 内容检查 ---"
unzip -l "$APK" | grep -cE "classes.*\.dex"
unzip -l "$APK" | grep -E "AndroidManifest.xml|resources.arsc" | head -2
