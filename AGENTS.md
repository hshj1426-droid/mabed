<!-- Codex용. CLAUDE.md 와 같은 내용이다. 한쪽을 고치면 다른 쪽도 같이 고칠 것. -->

# 마베드 (mabed) — 작업 지침

버킷츠 Ma Bed 2M+ 모션베드를 조작하는 안드로이드 앱. 제조사 앱이 2026년 5월 서비스 종료되어 직접 만든 대체 앱이다.
**폰이 곧 서버다.** 침대 제어 기판이 폰으로 접속해 오고, 폰이 명령을 내려보낸다. 중앙 서버는 없다.

처음 맡았다면 `HANDOVER.md`(있으면)를 먼저 읽을 것. 프로토콜은 `docs/PROTOCOL.md`, 지난 실수는 `docs/HISTORY.md`.

## 사용자

- 이 앱의 주인은 비개발자(문과 배경)다. **설명은 쉬운 말로, 전문 용어 없이.** 필요한 용어는 풀어서 쓴다.
- 한국어로 대화한다. 앱 화면 문구도 전부 한국어.
- 실제 침대와 폰은 사용자에게만 있다. 에이전트는 기기에서 직접 확인할 수 없으므로, **확인 안 된 것을 확인됐다고 말하지 말 것.** 사용자가 설치해서 확인해야 하는 항목은 분명히 적어서 넘긴다.

## 절대 규칙

1. **Gradle·AndroidX·XML 레이아웃을 쓰지 않는다.** 화면은 전부 자바 코드로 만든다 (`Ui.java`의 부품 공장). 외부 라이브러리를 추가하지 않는다.
2. **minSdk 24, targetSdk 35.** 24보다 새 API는 반드시 `Build.VERSION.SDK_INT >=` 로 감싼다. 제조사 롬에서 터질 수 있는 호출은 try/catch. 앱이 죽으면 사용자는 침대를 못 움직인다.
3. **`BedServer.java`의 통신 규칙을 바꾸지 않는다.** 침대 펌웨어는 고칠 수 없다. 바이트 하나 틀리면 침대가 붙지 않는다.
4. **저장 방식(SharedPreferences 키·구조)을 바꾸면 이전 버전 설정을 옮기는 코드를 반드시 같이 넣는다.** 4.0.0에서 이걸 빠뜨려 사용자의 침대 등록이 전부 날아갔다. `migrateOld()` 참고. 기존 키 이름은 바꾸지 않는다.
5. **포그라운드 서비스 종류는 `connectedDevice`.** `dataSync`로 되돌리면 안드로이드 15에서 하루 6시간 뒤 서비스가 강제 종료되고, 부팅 자동 시작도 막힌다.
6. **`build.sh`의 `--min-sdk-version 24 --target-sdk-version 35`를 빼지 않는다.** 한 번 빠뜨려 4.6.0~5.0.0이 버전 표시 없이 나갔다.
7. **서명 키(`build/mabed.jks`)와 비밀번호(`build/ks_pass.txt`)를 저장소에 올리지 않는다.** 저장소는 공개다. 키를 잃으면 기존 설치본 위에 덮어쓰기가 안 되고, 사용자가 앱을 지웠다 다시 깔면서 모든 설정을 잃는다.
8. **Stop(정지)은 V41에 `1`을 쓴다.** "현재 위치를 다시 보내서 멈추기" 방식은 오래된 값 때문에 침대를 0도로 몰아간 적이 있다.

## 코드 스타일

- 평범한 자바. 람다 대신 익명 내부 클래스 (기존 코드와 맞춘다).
- 주석과 화면 문구는 한국어. 주석은 "무엇을/왜"를 짧게.
- 새 화면 부품은 `Ui.java`에 만들고 `u.dp()`, `u.sp()` 배율을 쓴다. 숫자 크기를 직접 박지 않는다 (화면 크기 대응 때문).
- 네트워크는 절대 UI 스레드에서 하지 않는다 (`bg()` → `post()`). `NetworkOnMainThreadException`은 메시지가 `null`이라 "명령 실패 · null"로만 보인다.

## 구조

```
src/kr/mabed/control/
  MainActivity.java  화면 전체 — 메인 / 설정 화면 / 설정 마법사 / 넘겨주기 · 뒤로가기(onBackPressed)
  BedServer.java     Blynk Legacy TCP 서버 (포트 8080) — 손대지 말 것
  App.java           서버·이웃 창구(ApiServer)·이웃 찾기(LanPeers)를 앱 전체에 하나씩. 화면이 새로 떠도 새로 만들지 않는다 (startNet)
  ServerService.java 포그라운드 서비스. 앱을 닫고 5분 뒤 모두 끔("앱을 닫아도 대기" stayOn 이면 계속). WakeLock·WifiLock 은 "연결 유지 강화"일 때만
  BootReceiver.java  재부팅 후 서버 되살리기 (stayOn·serverOn 이고 침대가 있을 때만)
  LanPeers.java      같은 와이파이의 다른 폰 찾기 (UDP 9098). MulticastLock 은 화면이 보일 때만, 한 번 찾은 폰은 /state 로 직접 확인
  ApiServer.java     다른 폰이 보내는 명령 받기 (HTTP 9099) — 5.9.0부터 짝지은 폰만. /hello·/pair 외엔 집 열쇠 도장(HMAC) 필수
  HomeKey.java       집 열쇠(짝짓기)·도장 만들기/확인
  Beds.java          등록 침대 목록 (prefs "beds" JSON)
  Net.java           와이파이 주소, 침대 AP 통신, isLan()
  Updates.java       깃허브 릴리스로 새 버전 확인 (REPO = hshj1426-droid/mabed)
  Updater.java       새 버전 내려받아 PackageInstaller 로 설치 (배경 자동 확인 없음 — 앱을 켤 때 확인하고 묻는다)
  InstallReceiver.java 설치 결과 받기 — 확인 창은 리시버가 띄우지 않고 화면(handleInstall)에 넘긴다 (안드로이드 14+ 가 막음)
  Ui.java            색·버튼·카드·배율
  BedView.java       침대 옆모습 그림 (mini 모드는 자세 버튼 아이콘)
  Slider.java        직접 그린 슬라이더
  Glyph.java         직접 그린 아이콘
res/                 아이콘과 앱 이름뿐
.github/workflows/build.yml  릴리스 발행 시 APK 자동 빌드
```

## 빌드

```
./build.sh 5.9.1          # → build/mabed-5.9.1.apk
```

- 필요: `~/android-sdk` (또는 `ANDROID_HOME`)에 build-tools 35.0.0, platforms/android-35. JDK 17+.
- 필요: `build/mabed.jks`, `build/ks_pass.txt` (저장소에 없음. 사용자에게 받는다)
- 버전을 올릴 때 `AndroidManifest.xml`의 `versionCode`(정수, 반드시 증가)와 `versionName`을 같이 올린다.

## 검증 (에뮬레이터가 없을 때)

```
BT=$ANDROID_HOME/build-tools/35.0.0
$BT/aapt2 dump badging build/mabed-X.apk | head -3     # minSdk 24, targetSdk 35 확인
$BT/aapt2 dump xmltree --file AndroidManifest.xml build/mabed-X.apk | grep -A1 "E: service"
#   foregroundServiceType 0x10 = connectedDevice (0x01 이면 dataSync — 잘못됨)
unzip -p build/mabed-X.apk classes.dex | strings | grep kr/mabed/control   # 새 클래스 들어갔는지
```

9099 짝짓기·도장을 고쳤다면 `test/pairing/run.sh <android.jar> <org.json.jar>` 를 돌릴 것 (35개 확인, PC 에서 실제 코드로). org.json 진짜 jar 는 Android Studio 의 plugins/grazie/lib/org.json-json.jar 에 있다.

화면 모양은 에뮬레이터 없이 확인할 수 없다. 레이아웃을 크게 바꿨다면 사용자에게 스크린샷을 부탁한다.

## 배포

- 사용자에게 APK를 직접 주거나,
- `main` 에 올리면 **점검**(check.yml: 비밀 유출 검사·컴파일·짝짓기 시험)이 돈다. 빨간불이면 릴리스하지 말 것.
- 깃허브에서 **릴리스를 발행**하면(태그 `v<versionName>` 형식, 다르면 빌드가 멈춤) Actions가 서명한 APK를 만들어 그 릴리스에 붙인다. 앱은 켤 때(1시간 간격) 최신 릴리스를 확인해 설치할지 묻는다. 설정 → 새 버전 확인하고 설치.
- 금고(Secrets)에 `KEYSTORE_B64`(키 파일을 base64), `KEYSTORE_PASS`가 있어야 한다.

## 침대 핀 요약

| 핀 | 뜻 | 값 |
|----|----|----|
| V11 | 상체 각도 | 0~80 |
| V13 | 다리 각도 | 0~45 |
| V14 | 테이블 | 0~850 · 테이블 미설치 제품은 이 핀이 다리를 움직인다. 설정에서 켠 경우만 노출 |
| V41 | 정지 | 1 (쓰기 전용) |
| V52 | 무드등 | 0/1 |
| V61 | 스피커 | 0/1 · 켠 뒤 블루투스 `XDADADZ` 연결 |
| V8, V15 | 미확인 | |

침대는 움직이는 동안 값 요청(`vr`)에 답하지 않는다. 2.4GHz 와이파이만 된다.
