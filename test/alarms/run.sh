#!/bin/bash
# 알람 값 형식 시험 — 침대에 보내는 Time Input 값과 한국 시각→UTC 변환(요일 넘김 포함)
#   ./test/alarms/run.sh <android.jar>
set -e
ANDROID_JAR="$1"; [ -f "$ANDROID_JAR" ] || { echo "사용법: $0 <android.jar>"; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; W="$ROOT/build/alarmtest"
rm -rf "$W"; mkdir -p "$W"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; W="$(cygpath -w "$W")"; ROOT="$(cygpath -w "$ROOT")";; esac
javac --release 17 -nowarn -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$W" \
  "$ROOT/src/kr/mabed/control/Alarms.java" "$ROOT/test/pairing/stub-ServerService.java" \
  "$ROOT/src/kr/mabed/control/App.java" "$ROOT/src/kr/mabed/control/ApiServer.java" "$ROOT/src/kr/mabed/control/BedServer.java" \
  "$ROOT/src/kr/mabed/control/Beds.java" "$ROOT/src/kr/mabed/control/HomeKey.java" "$ROOT/src/kr/mabed/control/LanPeers.java" \
  "$ROOT/src/kr/mabed/control/Net.java" "$ROOT/test/alarms/src/kr/mabed/control/AlTest.java"
java -Duser.timezone=Asia/Seoul -Dstdout.encoding=UTF-8 -cp "$W$SEP$ANDROID_JAR" kr.mabed.control.AlTest
