# Pleos SDK에서 확인한 것

Pleos Connect 에뮬레이터 2.1.0에서 직접 돌려 보고 확인한 내용이다(2026-11). 연동 구현과 재현에 필요한 환경별 관찰을 남긴다. 실차에서는 다를 수 있다.

## 쓰는 SDK와 권한

Maven 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`이고, 개발 환경에서 인증 없이 내려받았다. 필요한 권한은 `AndroidManifest.xml`에 선언하며, 위치·알림·오버레이는 실행 환경에서 허용해야 한다.

| SDK | 버전 | 권한 | 쓰는 곳 |
|---|---|---|---|
| Vehicle | 2.3.2 | `CAR_DRIVE`, `CAR_DOORS`, `CAR_ENERGY` | 기어, 문, 주행거리계, 배터리·연료, 누적 전비·연비. 좌석 탑승(`CAR_SEATS`)은 11/15부터 쓰지 않는다(아래) |
| NaviHelper | 3.0.5 | `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP` | 경로 시작(목적지, 예상 통행료), 톨게이트 통과, 도착, 현재 주소 |
| TextToSpeech | 3.3.1 | `TTS_SERVICE` | 도착 정산 한 문장, Gleo 응답 |
| FusedLocation | 1.1.3 | `FUSED_LOCATION`(실행 중에 요청) | 탄 곳·내린 곳 좌표 |

## 차량 신호 (Vehicle)

- **좌석 탑승 값이 반대로 온다.** `true`가 빈자리다. 뒷좌석(`ROW_2_LEFT`, `ROW_2_RIGHT`)도 같다. 뒷줄 가운데(`ROW_2_CENTER`)는 인식되지 않는다. 그래서 11/15부터 좌석 센서를 쓰지 않고 사람이 C-04·C-05로 알려 준다(`CarSignals.kt`에서 등록하지 않음, 권한도 뺐다).
- 자리는 `SeatArea(SeatValue.ROW_1_LEFT … ROW_3_RIGHT)`, 문은 `DoorArea(DoorValue)`로 고른다.
- 주행거리계는 `registerDistanceDriven`(km)이다. 구간 거리는 이 값의 차이로 잰다. 위치로 거리를 셀 필요가 없다.
- **배터리 잔량(`registerEvBatteryLevel`)은 Wh로 온다.** 예를 들어 77400.0은 77.4kWh다.
  - `registerEvBatteryStateOfCharge`는 %로 온다.
  - `registerCumulativeEvEfficiency`는 누적 전비다.
- `getEvBatteryCapacity`는 SecurityException("need Permission")을 던진다. 쓰지 않는다.
- 연료는 `registerFuelPercent`(%)와 `registerCumulativeFuelEconomy`(누적 연비)로 받는다.
- **차량 속도 API는 2.3.2에서 빠졌다.** 주행 중인지는 기어(D/R)로 판단한다.
- 리스너마다 `onFailed(e)`가 있다. 신호가 안 오면 수동 입력으로 넘어가야 한다.

## 내비 (NaviHelper)

- `onRouteStarted`로 목적지 이름과 예상 통행료(`tollFare`)가 온다.
- `onTBTInfo`의 `TurnType.TURN_TOLL`로 톨게이트를 안다. TBT에는 좌표가 없다.
- `onDestinationArrived`로 도착을, `onCurrentLocationInfo`로 현재 주소를 받는다.
- **차속은 Pleos Vehicle SDK에 없다.** Android 표준 차량 속성 `PERF_VEHICLE_SPEED`(`CarPropertyManager`, 권한 `android.car.permission.CAR_SPEED`, AAOS에서는 "위치" 묶음)로 읽을 수 있다. 에뮬레이터 가상 콘솔의 `controls?speed=`가 이 값을 바꾼다(아래 경고). Vehicle `Brake`는 페달을 밟았는지(켜짐/꺼짐)만 준다. 현재 앱은 급가속·급제동 코칭을 제공하지 않으며 차속을 읽지 않는다. 공인연비 기준연비의 도심·고속 구분도 차속 대신 주행거리 ÷ 걸린 시간(1km마다)으로 한다. 차속은 한 번에 오는 값이라 1km 평균을 내려면 계속 받아야 하는데, 주행거리 ÷ 시간이면 새 권한 없이 같은 평균 차속이 나온다.
- **하이패스 실제 결제 금액을 주는 API는 없다.** Vehicle의 `Toll`에는 ETCS 음성 볼륨만 있다. 통행료는 내비 예상값을 쓰고, 사람이 고칠 수 있게 둔다.
- 경유지는 최대 2개다.

## 음성 (TextToSpeech, Gleo)

- TTS는 `Mode.ON_DEVICE`에 `Locale.KOREA`로 쓴다. 서버 모드는 Client ID가 필요한데 발급 메뉴가 "준비 중"이다.
- Gleo 액션은 `assets/schema.json`에 **최대 3개**까지 넣는다.
  - broadcast의 action이 함수 이름 그대로 온다.
  - 파라미터는 `ACTION_PARAM`에 JSON으로 온다.
  - 응답은 7초 안에 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`로 보낸다.
- 공모전 참가팀은 [운영국 개발 범위 안내](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000008)에 따라 Gleo Interface를 별도 승인 없이 사용할 수 있다. 구현은 같은 broadcast를 adb로 보내 확인했으며(`tools/demo.sh gleo ...`), 실제 음성 인식부터 앱 호출까지의 연속 동작은 별도 확인 대상이다.

## 화면과 백그라운드

- 에뮬레이터 화면은 2560×1440, 밀도는 160이다. 앱 영역은 약 1700×1184라서 기획서 px를 그대로 dp로 쓰면 된다.
- 주행 중에는 내비가 앞에 있다. 백그라운드에서 앱을 앞으로 띄우거나 팝업을 겹쳐 띄우려면 `SYSTEM_ALERT_WINDOW`가 필요하다.
  - 에뮬레이터에서는 `adb shell appops set --user 10 com.nbbang SYSTEM_ALERT_WINDOW allow`로 켠다.
- 앱은 사용자 10에 설치하고 실행한다(`--user 10`).
- 차 신호는 포그라운드 서비스(`RideService`)가 받아야 화면이 꺼져도 기록된다.

## 에뮬레이터 가상 콘솔

- 주소는 `127.0.0.1:8088`이다. 먼저 `adb forward tcp:8088 tcp:8088`을 한다.
- 1초마다 `POST /browser/session`을 보내야 값이 차에 전달된다.
- **차속(`POST vehicle/controls?speed=`)을 보내지 말 것.** `-gpu swiftshader_indirect`로 띄운 에뮬레이터는 D에서 차속을 받으면 계기판 3D 주행 화면이 움직이기 시작하고, 1분 안에 Windows 오류 기록 없이 꺼진다. 기어와 주행거리만 보내면 50분 넘게 괜찮았다(11/15). 앱은 차속을 쓰지 않으니 `tools/demo.sh`도 보내지 않는다.
- 좌석: `POST vehicle/seat?area=passenger|rear_left|rear_right&value=true&kind=occupied`
- 문: `PUT vehicle/body?doors_locked=0&door_fl=1`(fl·fr·rl·rr)
- 주행거리: `POST vehicle/odometer?traveled_distance_km=X`
  - **주행거리계는 줄어들지 않는다.** 낮은 값을 보내면 무시된다.
- 배터리: `POST vehicle/ev-battery?field=current_energy&value=<kWh>`
  - `field=soc`로는 에너지가 바뀌지 않는다.
  - `field=capacity`를 바꾸면 최대 용량이 바뀌니 조심한다.
- 기어: `POST vehicle/gear?gear=Drive|Park`
- 하이패스(ETCS) 시나리오는 콘솔에 있지만, 앱에서 결제 내역을 읽을 SDK는 없다.
- 긴 시나리오 끝에서 에뮬레이터가 꺼진 적이 있다(Windows 그래픽 드라이버 시간 초과). 시연은 짧게 끊어서 하는 편이 안전하다.

## 공개 SDK에 없는 것

- 카투페이 등 차량 결제 내역
- 하이패스 실제 결제 금액
- 차에서 휴대폰으로 문자·알림 보내기

현재 구현은 수동 입력과 QR 청구서를 사용한다. 위 기능을 구현한 것으로 안내하지 않는다.
