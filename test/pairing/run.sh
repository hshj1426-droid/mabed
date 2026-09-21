#!/bin/bash
# 9099 짝짓기·도장 통합 시험 — 실제 ApiServer / LanPeers / HomeKey / BedServer 를 PC 에서 두 프로그램으로 돌린다.
#   (서버 폰 = PairServer, 요청하는 폰 + 가짜 침대 = PairTest)
# 필요: JDK 17+, android.jar(platforms/android-35), org.json 진짜 구현 jar (android.jar 의 org.json 은 껍데기뿐)
#   ./test/pairing/run.sh <android.jar> <org.json.jar>
# 9099·9098·8080 포트를 쓰므로 PC 에서 다른 게 쓰고 있으면 실패한다. PC 에 집 안 주소(192.168.x 등)가 있어야 한다.
set -e
ANDROID_JAR="$1"; JSON_JAR="$2"
[ -f "$ANDROID_JAR" ] && [ -f "$JSON_JAR" ] || { echo "사용법: $0 <android.jar> <org.json.jar>"; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$ROOT/test/pairing"; W="$ROOT/build/pairtest"
rm -rf "$W"; mkdir -p "$W/cls" "$W/out" "$W/gen"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; W="$(cygpath -w "$W")"; T="$(cygpath -w "$T")"; ROOT="$(cygpath -w "$ROOT")";; esac
# 앱 코드 (R 없이 컴파일되는 파일만 — 시험에 필요한 것)
APP_SRC=()
for n in Alarms App ApiServer BedServer Beds HomeKey LanPeers Net; do APP_SRC+=("$ROOT/src/kr/mabed/control/$n.java"); done
javac --release 17 -nowarn -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$W/cls" \
  "${APP_SRC[@]}" "$T/stub-ServerService.java"
# 가짜 Context·저장소가 진짜보다 앞에 오게
javac -nowarn -encoding UTF-8 -d "$W/out" -cp "$W/cls$SEP$JSON_JAR$SEP$ANDROID_JAR" \
  "$T"/stubs/android/content/*.java "$T"/src/kr/mabed/control/*.java
CP="$W/out$SEP$JSON_JAR$SEP$W/cls$SEP$ANDROID_JAR"
java -Dstdout.encoding=UTF-8 -cp "$CP" kr.mabed.control.PairTest "$W" "$(command -v java)" "$CP"
