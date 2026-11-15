# N빵~

**함께 탄 구간에 맞춰 운전 비용을 나누는 Pleos Connect 차량용 앱.**

카풀과 여행에서는 중간에 타거나 내리는 사람이 있고, 통행료·주차비를 낸 사람도 다르다. N빵은 차량의 주행거리와 내비 정보를 이용해 구간을 기록하고, 각 구간에 함께 탄 사람에게 비용을 나눈다. 주차 후에는 사람별 QR 청구서를 만들어 휴대폰으로 전달한다.

- **카풀:** 자주 타는 그룹을 저장하고, 그날 정산하거나 한 달 내역을 모아 청구한다.
- **여행:** 출발 명단과 중간 탑승·하차를 기록하고, 유류비·통행료·추가 비용을 함께 정산한다.
- **차량 연동:** 내비가 앞에 있는 동안에도 기록을 이어 간다. 주행 중 입력은 팝업과 선택 버튼을 중심으로 제한한다.

팀명: **N빵**

이 저장소는 팀의 기존 개발 저장소에서 소스·테스트·release APK를 모아 공개한 **공모전 심사용 제출본**이다. 원본 개발 이력은 별도로 보존하고 있다. 실제 개발 단계와 설계 변경, 수정 사례는 [개발 과정과 검증 근거](docs/DEVELOPMENT.md)에 정리했다.

## APK와 실행

[제출용 release APK 내려받기](apk/N빵_N빵_release.apk?raw=true)

앱 ID는 `com.nbbang`, 버전은 `0.1.0`이다. Pleos Connect 에뮬레이터에서 실행한다. 일반 휴대폰용 앱은 아니다.

| 항목 | 개발·검증 환경 |
|---|---|
| Pleos Connect 에뮬레이터 | 2.1.0, Android API 34 |
| 전체 화면 | 2560 × 1440px |
| 앱 영역 | 약 1700 × 1184px, 화면 밀도 160dpi |
| 설치 사용자 | 에뮬레이터 사용자 10 |
| 소스 빌드 | JDK 17, Android SDK Platform 34, Build Tools 34.0.0, Gradle Wrapper 8.7 |

```bash
adb install --user 10 apk/N빵_N빵_release.apk
adb shell am start --user 10 -n com.nbbang/.MainActivity
```

처음에는 P단에서 앱을 열고 연료 종류·단가·연비와 받을 계좌 또는 송금 링크를 설정한다. 위치 권한은 탑승·하차 위치를 기록하고 최근 탑승자 이름을 제안하는 데 사용한다. 내비 위에 주행 팝업을 표시하려면 다른 앱 위에 표시 권한이 필요하다. 테스트 에뮬레이터에서는 다음 명령으로 허용할 수 있다.

```bash
adb shell appops set --user 10 com.nbbang SYSTEM_ALERT_WINDOW allow
```

이미 다른 서명으로 설치한 `com.nbbang`이 있으면 업데이트 설치가 실패할 수 있다. 기존 기록이 있는 기기 대신 별도의 테스트 에뮬레이터를 사용한다.

## 사용 흐름

1. **출발 전:** 카풀 그룹 또는 여행을 선택하고 함께 탈 사람을 정한다.
2. **주행 중:** 차량 주행거리로 구간을 기록한다. 중간에 사람이 바뀌면 탑승·하차 버튼으로 알려 준다. 톨게이트와 추가 비용은 해당 구간의 탑승자에게 배분한다.
3. **주차 후:** 주행 종료를 누르고 비용과 참여 인원을 확인한다. 문을 열었다는 이유만으로 주행을 끝내지 않는다.
4. **청구:** 균등·구간 정산 또는 게임 결과를 확인하고 사람별 QR 청구서를 만든다. 카풀은 월별 청구도 가능하다.
5. **입금 관리:** 입금 여부를 직접 표시하고 남은 청구를 확인한다. 앱이 송금을 실행하거나 입금을 자동 확인하지는 않는다.

## 구현한 기능

| 기능 | 구현 내용 |
|---|---|
| 구간 기록 | 차량 주행거리, 수동 탑승·하차, 재탑승, 진행 중 주행 저장·복원 |
| 정산 | 구간별 유류비·전기요금, 통행료, 주차비 등 추가 비용, 선결제·참여 제외 반영 |
| 운전자 절약 기여 | 기준연비와 실제 전비·연비로 절약액을 추정하고, 동승자 확인 후 운전자 몫에 반영 |
| 여행 게임 | 사다리타기, 컵 돌리기, 카드 뽑기, 주사위 굴리기 결과를 정산에 반영 |
| 카풀 | 그룹·좌석 선택, 당일 또는 월별 정산, 청구·미납 관리 |
| QR 청구서 | 사람별 금액과 내역, 계좌 복사, HTTPS 송금 링크. 별도 휴대폰 앱 설치 불필요 |
| 음성 | Pleos TTS 안내, Gleo 액션 3개(정산 보기·쏘기·비용 추가) |

정산은 `core` 모듈에서 원 단위로 계산한다. 원 단위로 내리면서 생긴 차액은 화면에서 별도로 표시한다. 공인연비 기반 절약액과 내비 통행료는 추정값으로 안내하고, 단가·비용은 사용자가 확인하고 수정할 수 있다.

## 소스 빌드와 테스트

Android Studio에서 저장소를 열거나 `ANDROID_HOME`을 Android SDK 경로로 지정한다. 첫 빌드는 Gradle·Google Maven·Maven Central·Pleos Maven 저장소에 접근할 네트워크가 필요하다.

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew --no-daemon :app:lintDebug
```

서명된 release APK를 직접 빌드하는 방법은 [빌드·검증 안내](docs/BUILD.md)에 있다. 공개 소스에는 서명 키를 포함하지 않으며, 설정 없이 release를 빌드하면 unsigned APK가 생성된다.

Windows 명령 프롬프트에서는 `./gradlew` 대신 `gradlew.bat`을 쓴다. 빌드한 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생긴다. 단위 테스트는 에뮬레이터 없이 실행할 수 있다.

정산·구간·청구 단위 테스트와 배터리 단위 변환 회귀 테스트를 포함한다. 최종 release 빌드의 검증 결과와 SHA-256은 [빌드·검증 안내](docs/BUILD.md)에 기록했다.

## 차량 SDK와 시연의 범위

| 구성요소 | 버전 | 쓰는 정보 |
|---|---|---|
| Vehicle | 2.3.2 | 기어, 누적 주행거리, 문, 배터리·연료, 누적 전비·연비 |
| NaviHelper | 3.0.5 | 경로·목적지, 예상 통행료, 톨게이트 통과, 도착, 주소 |
| TextToSpeech | 3.3.1 | 안내와 응답 음성 |
| FusedLocation | 1.1.3 | 탄 곳·내린 곳 좌표 |

- **탑승자는 수동으로 기록한다.** 뒷좌석 가운데 센서의 한계 때문에 좌석 센서를 사용하지 않는다.
- **통행료는 실제 하이패스 결제내역이 아니다.** 내비 예상값과 사용자가 확인한 값을 사용한다.
- **제출 APK에는 시연용 broadcast 수신기가 없다.** `app/src/debug`의 수신기는 디버그 빌드에만 포함되며, `tools/demo.sh`는 이 수신기와 가상 콘솔을 사용하는 재현 도구다. 에뮬레이터의 내비가 움직이지 않는 시나리오에서는 경로·톨게이트·도착 신호를 이 도구로 넣었다.
- **Gleo는 같은 형식의 broadcast로 시험했다.** App Schema와 액션 수신기를 구현했으며, 실제 Gleo 음성 인식부터 앱 액션 호출까지의 연속 동작과 실차 동작은 확인하지 않았다.
- 공시 유가 조회는 오피넷 인증키가 있어야 동작한다. 공개 APK에는 키를 넣지 않으며, 키가 없어도 설정 단가로 정산할 수 있다.

환경별 관찰과 시연 도구 사용 시 주의점은 [Pleos SDK 확인 기록](docs/PLEOS_SDK.md)에 있다. `tools/demo.sh`는 연결된 에뮬레이터의 상태와 시연 데이터를 바꾸므로 별도 테스트 환경에서 사용한다.

## 데이터와 QR 청구서

주행 장부와 설정은 차량 앱의 로컬 저장소에 저장한다. QR을 찍으면 [휴대폰 청구서](https://hyundaiapp.github.io/pay/)가 열린다. 청구 내용은 URL의 `#` 뒤에 담으며 청구서 페이지 서버로 전송하지 않는다. 다만 브라우저 방문 기록이나 공유한 URL에는 남을 수 있으므로 QR과 주소는 받을 사람에게만 전달한다.

페이지 소스는 [web/pay.html](web/pay.html)이다. 다른 호스팅 주소를 쓰려면 빌드 시 `-Pnbbang.payUrl=https://example.com/pay.html`로 지정할 수 있다. 오피넷 키·서명 키·개인 설정은 소스에 포함하지 않는다.

## 코드 구조

```text
core/       안드로이드에 의존하지 않는 주행 모델·정산 엔진·청구 로직과 단위 테스트
app/        Compose 화면, Pleos SDK 연결, 차량 로컬 저장, 기록 서비스
web/        휴대폰 QR 청구서 페이지와 배경 리소스
tools/      디버그 시연 도구
docs/       환경과 SDK 확인 기록
third_party/ 제3자 구성요소의 출처와 라이선스
apk/        제출용 release APK
```

정산 규칙은 [Settlement.kt](core/src/main/kotlin/com/nbbang/settle/Settlement.kt), 차량 기록을 정산 입력으로 바꾸는 부분은 [RideSettlement.kt](core/src/main/kotlin/com/nbbang/settle/RideSettlement.kt), 주행 기록은 [RideTracker.kt](core/src/main/kotlin/com/nbbang/domain/RideTracker.kt)에서 확인할 수 있다.

## 사용한 구성요소

- Asta Sans: SIL Open Font License 1.1, [저작권·라이선스](third_party/asta-sans/OFL.txt)
- ZXing core, Lottie Compose, AndroidX·Jetpack Compose, Kotlin Coroutines: Apache 2.0
- JUnit 4: Eclipse Public License 1.0
- Pleos Connect SDK: 각 SDK의 이용 조건 적용

출처와 라이선스 원문은 [제3자 구성요소](third_party/README.md)에 정리했다.

이 저장소는 2026 현대자동차그룹 차량용 앱 개발 공모전 심사를 위한 제출본이다. 출품작의 권리는 제작 팀에 있으며, 별도의 오픈소스 이용허락을 부여하지 않는다. 제3자 구성요소에는 각 라이선스가 적용된다.
