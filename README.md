# 마베드 (mabed)

버킷츠 Ma Bed 2M+ 모션베드를 조작하는 안드로이드 앱.
제조사(birkits)가 2026년 5월 앱 서비스를 종료해서 직접 만든 대체 앱.

## 문서

| 파일 | 내용 |
|------|------|
| `CLAUDE.md` / `AGENTS.md` | AI 코딩 도구(Claude Code, Codex)용 작업 규칙 |
| `docs/PROTOCOL.md` | 침대 통신 규칙, 설정 모드, 핀 번호 |
| `docs/HISTORY.md` | 버전 기록과 지난 실수 |

## 어떻게 동작하나

침대 속 제어 기판은 **Blynk Legacy** 라는 옛날 IoT 방식으로 통신한다.
원래는 제조사 서버(birkits.blynk.cc:443)로 접속했는데,
침대를 설정할 때 **접속할 서버 주소를 직접 넣을 수 있다**는 점을 이용했다.

그래서 이 앱은 **폰 자체가 서버**다.
- 폰이 8080 포트로 Blynk 서버를 연다 (`BedServer.java`)
- 침대를 재설정해서 그 폰의 집 와이파이 주소로 접속하게 만든다 (마법사)
- 같은 와이파이의 다른 폰은 UDP 9098 로 서로를 찾고, HTTP 9099 로 명령을 중계한다
  (`LanPeers.java`, `ApiServer.java`)

침대 하나당 "주인 폰"이 하나. 주인 폰이 집에 없으면 그 침대는 아무도 못 쓴다.

## 침대 핀 번호 (직접 알아낸 값)

| 핀 | 뜻 | 비고 |
|----|-----|------|
| V11 | 상체 각도 | 0~80 |
| V13 | 다리 각도 | 0~45 |
| V14 | 테이블 | 0~850, 테이블 미설치 제품은 사용 금지 |
| V41 | **정지** | 쓰기 전용. 1 을 보내면 그 자리에서 멈춤 |
| V52 | 무드등 | 0/1 |
| V61 | 스피커 | 0/1, 켠 뒤 블루투스 XDADADZ 연결 |
| V8, V15 | 미확인 | |

침대는 움직이는 동안 값을 묻는 명령(vr)에 답하지 않는다.
2.4GHz 와이파이만 된다. 5GHz 는 목록에 아예 안 뜬다.

## 빌드

Gradle 도 AndroidX 도 안 쓴다. 수동 도구 사슬.

```
./build.sh 5.4.0      # build/mabed-5.4.0.apk 가 나온다
```

필요한 것:
- `~/android-sdk` (build-tools 35.0.0, platforms/android-35)
- JDK 17 이상
- `build/mabed.jks` — **서명 키. 절대 잃어버리면 안 된다.** (저장소에는 없다)
- `build/ks_pass.txt` — 서명 키 비밀번호 한 줄 (저장소에는 없다)
  이 둘이 없으면 기존 설치본에 덮어쓰기가 안 된다.
  깃허브 자동 빌드는 금고(Secrets)의 `KEYSTORE_B64`, `KEYSTORE_PASS` 를 쓴다.

버전을 올릴 때는 `AndroidManifest.xml` 의 versionCode 와 versionName 을
함께 올리고 `./build.sh <새버전>` 을 돌린다.

## 파일 구성

| 파일 | 하는 일 |
|------|---------|
| `MainActivity.java` | 화면 전체 — 메인/설정 화면/설정 마법사 |
| `BedServer.java` | Blynk 프로토콜 TCP 서버 (건드리지 말 것) |
| `ServerService.java` | 앱을 내려도 서버가 살아있게 하는 서비스 |
| `BootReceiver.java` | 폰 재부팅 후 서버 되살리기 |
| `LanPeers.java` | 같은 와이파이의 다른 폰 찾기 (UDP 9098) |
| `ApiServer.java` | 다른 폰이 보내는 명령 받기 (HTTP 9099) |
| `Beds.java` | 등록된 침대 목록 저장 |
| `Net.java` | 와이파이 주소 확인, 침대 AP 통신 |
| `Ui.java` | 화면 부품 공장 (색·버튼·카드·크기 배율) |
| `BedView.java` | 침대 옆모습 그림 |
| `Slider.java` | 직접 그린 슬라이더 |
| `Glyph.java` | 직접 그린 아이콘 |

## 주의할 점

- **저장 방식을 바꾸면 반드시 이전 버전 설정을 옮기는 코드를 같이 넣을 것.**
  4.0.0 에서 이걸 빠뜨려서 등록한 침대가 전부 날아간 적이 있다.
  `migrateOld()` 참고.
- 안드로이드 15 부터 `dataSync` 종류의 백그라운드 서비스는 하루 6시간 제한이 있다.
  그래서 `connectedDevice` 를 쓴다. 되돌리지 말 것.
- `build.sh` 의 `--min-sdk-version 24 --target-sdk-version 35` 를 빼면
  안드로이드가 아주 오래된 앱으로 취급한다. 한 번 빠뜨려서 문제가 됐었다.
- 9099 포트는 5.9.0부터 짝지은 폰만 쓸 수 있다 (허용 버튼으로 짝짓기, 요청마다 HMAC 도장). 자세한 건 docs/PROTOCOL.md 5장.
