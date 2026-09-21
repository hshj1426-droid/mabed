#!/bin/bash
# 마베드 APK 빌드
#   ./build.sh 5.3.0
# 환경변수로 바꿀 수 있는 것:
#   ANDROID_HOME  안드로이드 SDK 위치 (기본: ~/android-sdk)
#   KS_PASS       서명 키 비밀번호. 없으면 build/ks_pass.txt 에서 읽는다
#   KEYSTORE      서명 키 파일      (기본: build/mabed.jks)
# build/ 폴더는 저장소에 올라가지 않는다(.gitignore). 키와 비밀번호는 거기 둔다.
set -e

V="$1"
if [ -z "$V" ]; then echo "사용법: ./build.sh <버전>   예) ./build.sh 5.3.0"; exit 1; fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
KS="${KEYSTORE:-$ROOT/build/mabed.jks}"
if [ -z "$KS_PASS" ] && [ -f "$ROOT/build/ks_pass.txt" ]; then
  KS_PASS="$(tr -d '\r\n' < "$ROOT/build/ks_pass.txt")"
fi
[ -n "$KS_PASS" ] || { echo "서명 키 비밀번호가 없습니다. KS_PASS 환경변수나 build/ks_pass.txt 를 준비하세요."; exit 1; }
KS_ALIAS="${KS_ALIAS:-mabed}"

for f in "$BT/aapt2" "$PLAT" "$KS"; do
  [ -e "$f" ] || { echo "없습니다: $f"; exit 1; }
done

rm -rf build/classes build/dex build/gen build/res
mkdir -p build/classes build/dex build/gen build/res

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLAT" --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 \
  --java build/gen build/res.zip

javac --release 17 -encoding UTF-8 -nowarn -classpath "$PLAT" -d build/classes \
  $(find src build/gen -name '*.java')

"$BT/d8" --lib "$PLAT" --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/app-unsigned.apk
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)

rm -f build/app-aligned.apk "build/mabed-$V.apk"
"$BT/zipalign" -p -f 4 build/app-unsigned.apk build/app-aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "build/mabed-$V.apk" build/app-aligned.apk

echo "완성: build/mabed-$V.apk"
