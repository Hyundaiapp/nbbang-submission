# 개발 과정과 검증 근거

N빵은 구간별 비용 계산에서 시작해 차량 신호 연결, 카풀·여행 화면, 청구·납부 기록으로 구현 범위를 넓혔다. 개발 중에는 차량 신호의 한계와 정산 예외를 확인하고 동작을 수정했다. 이 문서는 원본 개발 저장소의 커밋·PR 설명과 현재 소스·테스트를 대조해 정리한 기록이다.

## 개발 저장소와 제출본의 관계

공개 저장소의 최초 커밋은 심사용 소스와 APK를 묶은 시점이다. 실제 개발은 별도의 팀 저장소에서 기능별 브랜치와 PR로 진행했으며, 원본 커밋·PR 기록은 그곳에 보존하고 있다. 제출본에서는 개발자 식별정보가 포함된 이력을 분리하고 심사에 필요한 소스·테스트·문서·APK를 공개했다.

아래 날짜는 원본 개발 저장소에 변경이 반영된 날을 한국시간으로 표시한 것이다. 마지막 제출 준비 항목은 별도 준비 브랜치에서 반영했다. 각 사례의 링크는 이 공개 제출본에 포함된 구현과 테스트로 연결된다.

## 구현이 쌓인 과정

| 시기 | 개발 내용 | 현재 제출본에서 확인할 곳 |
|---|---|---|
| 2026-11-10 | 초기 MVP를 개발 저장소에 등록하고 공통 디자인, 홈·설정 화면, 주행 현황 팝업을 연결했다. | [홈 화면](../app/src/main/java/com/nbbang/ui/home/HomeScreen.kt), [설정](../app/src/main/java/com/nbbang/ui/settings/SettingsScreen.kt) |
| 2026-11-11 | 구간별 정산 엔진, 탑승·하차 처리, 주행 저장·복원, 게임 정산, QR 청구서와 카풀 그룹을 구현하고 화면에 연결했다. | [정산 엔진](../core/src/main/kotlin/com/nbbang/settle/Settlement.kt), [주행 기록](../core/src/main/kotlin/com/nbbang/domain/RideTracker.kt), [청구서](../core/src/main/kotlin/com/nbbang/settle/Invoice.kt) |
| 2026-11-12 | 카풀 주행 요약부터 당일·월별 정산 선택, 월별 청구까지 연결했다. 받은 금액 이후의 추가 청구를 보완하고, 주행 종료를 명시적으로 확인하는 흐름으로 바꿨다. | [카풀 정산 선택](../app/src/main/java/com/nbbang/ui/carpool/CarpoolSettlementChoiceScreen.kt), [월별 청구](../app/src/main/java/com/nbbang/ui/carpool/CarpoolMonthlyBillingScreen.kt) |
| 2026-11-13~14 | 출발 전 절약 모드 선택 단계를 줄이고 급가속·급제동 음성 코칭을 제거했다. 정산·청구와 직접 관련된 흐름으로 범위를 정리했다. | [여행 화면](../app/src/main/java/com/nbbang/ui/trip/TripScreens.kt), [절약 기여 확인](../app/src/main/java/com/nbbang/ui/settle/SavingPopup.kt) |
| 2026-11-14 | 코드 검토에서 발견한 일부 납부·납부 취소·월 청구 금액 감소 문제를 수정하고 회귀 테스트를 추가했다. 게임·청구서 화면도 함께 개선했다. | [청구 기록 테스트](../core/src/test/kotlin/com/nbbang/domain/ChargesTest.kt), [청구서 테스트](../core/src/test/kotlin/com/nbbang/settle/InvoiceTest.kt) |
| 2026-11-15 | 공인연비를 활용한 절약액 추정을 보완하고, 좌석 센서를 쓰던 탑승 기록을 수동 확인 방식으로 바꿨다. 오피넷 인증키 처리와 시연 도구도 점검했다. | [기준연비](../core/src/main/kotlin/com/nbbang/domain/FuelEconomy.kt), [차량 신호](../app/src/main/java/com/nbbang/platform/CarSignals.kt), [SDK 확인 기록](PLEOS_SDK.md) |
| 2026-11-15 · 제출 준비 | 낮은 배터리 잔량의 단위 변환 오류를 수정하고 앱 회귀 테스트를 추가했다. 외부 서명 설정, release APK, 실행 안내와 검증 결과를 정리했다. | [배터리 회귀 테스트](../app/src/test/java/com/nbbang/platform/BatteryLevelTest.kt), [빌드·검증 결과](BUILD.md) |

## 실제로 바꾼 결정과 수정 사례

### 1. 좌석 센서 대신 탑승·하차를 직접 확인

개발 에뮬레이터에서는 뒷좌석 가운데 자리를 좌석 신호로 인식하지 못했다. 센서만으로 탑승자를 정하면 뒤에 세 명이 앉은 상황에서 정산 참여자가 빠질 수 있었다.

11월 15일에는 좌석 신호 등록과 `CAR_SEATS` 권한을 제거했다. 출발 명단을 먼저 정하고, 중간에 타거나 내리는 사람은 화면에서 알려 주도록 바꿨다. 주행 중 추가할 탑승자는 최근 탑승자 목록에서 선택한다.

[NoSeatSensorTest](../core/src/test/kotlin/com/nbbang/domain/NoSeatSensorTest.kt)는 세 명과 출발해 10km에서 한 명이 내리고, 30km에서 한 명이 타고, 40km에서 끝나는 흐름을 확인한다. 동승자별 정산 거리는 40·40·10·10km이며, 좌석 신호가 없어도 사용자가 알려 준 탑승·하차가 기록된다.

현재 연결은 [CarSignals](../app/src/main/java/com/nbbang/platform/CarSignals.kt)와 [탑승 팝업](../app/src/main/java/com/nbbang/ui/driving/BoardingPopup.kt)에 있다. 테스트는 기록기 로직을 검증하며 실제 좌석 센서의 동작을 보증하지 않는다.

### 2. 휴게소에서 문을 열어도 같은 주행을 이어 가기

초기 구현은 P단에서 운전석 문이 열리면 주행을 끝냈다. 휴게소·주유소에서 잠깐 내리거나 짐을 꺼내는 경우에도 기록이 나뉘는 문제가 있어, 11월 12일에 종료 버튼으로 확인하는 방식으로 바꿨다.

[RideTrackerTest](../core/src/test/kotlin/com/nbbang/domain/RideTrackerTest.kt)의 `restStopWithDriverDoorOpenKeepsOneRide`는 10km 주행 후 P단에서 문을 열고 다시 출발해 총 30km를 한 주행으로 기록하는지 확인한다. 마지막 `finish` 호출 전까지 기록을 유지한다.

이 선택에는 사용자가 종료를 눌러야 한다는 조건이 있다. 별도로 앱 재시작 때 12시간을 넘긴 저장본은 마지막 기록 지점에서 종료하는 복원 규칙을 둔다. 일반 주행 중의 문 열림 처리와 이 복원 규칙은 구분된다.

### 3. 앱이 다시 시작돼도 진행 중인 정산을 이어 가기

차량 앱은 화면 전환이나 재시작을 겪을 수 있어, 완료한 주행뿐 아니라 진행 중인 구간·탑승자·비용도 보존해야 했다. 11월 11일에 기록기의 `snapshot`·`restore`와 로컬 저장을 연결했다. 사용자 변경은 즉시 저장하고, 그 외에는 상태를 갱신할 때 마지막 저장에서 30초가 지났는지 확인해 주행거리를 저장하도록 했다.

[RestoreTest](../core/src/test/kotlin/com/nbbang/domain/RestoreTest.kt)는 같은 주행을 중단 없이 진행한 경우와 중간 저장본을 새 기록기에 복원한 경우를 비교한다. 구간·탑승자·통행료·정산 금액이 같은지, 수동 탑승 정보가 유지되는지 확인한다.

저장 연결은 [RideViewModel](../app/src/main/java/com/nbbang/RideViewModel.kt)과 [LedgerStore](../app/src/main/java/com/nbbang/platform/LedgerStore.kt)에 있다. 이 단위 테스트는 메모리의 저장본 복원 검증이며, 운영체제의 강제 종료부터 디스크 복구까지 전 과정을 시험한 결과는 아니다.

### 4. 받은 돈을 빼고 추가분만 청구하기

11월 14일의 코드 검토에서, 받은 돈이 있는 여행 청구서가 다시 전액을 요청하는 문제를 수정했다. 예를 들어 10,000원을 받은 뒤 총액이 12,000원으로 늘어나면 청구 현황에는 2,000원이 남았지만 QR 청구서는 12,000원을 요청했다. 청구서를 만들 때 이미 받은 금액을 반영하고 남은 금액을 표시하도록 했다.

[InvoiceTest](../core/src/test/kotlin/com/nbbang/settle/InvoiceTest.kt)의 재청구 테스트는 받은 금액을 뺀 청구 금액과 내역 합계를 확인한다. [ChargesTest](../core/src/test/kotlin/com/nbbang/domain/ChargesTest.kt)는 다음 상태 변화를 확인한다.

- 10,000원 납부 후 12,000원으로 재청구하면 남은 금액은 2,000원이다.
- 나머지도 납부했다고 표시한 뒤 취소하면, 먼저 받은 10,000원 기록을 유지한다.
- 월별 정산 금액이 줄어들면 기존 청구를 조정하고, 받을 금액이 없어진 미납 청구를 정리한다.

구현은 [Charges](../core/src/main/kotlin/com/nbbang/domain/Charges.kt)와 [Invoice](../core/src/main/kotlin/com/nbbang/settle/Invoice.kt)에 있다. 납부 여부는 사용자가 기록하며 은행의 실제 입금 내역을 자동 조회하지 않는다.

### 5. 낮은 배터리 잔량에서도 Wh를 kWh로 변환

제출 점검에서 SDK 배터리 값을 크기에 따라 다른 단위로 해석하던 오류를 발견했다. 잔량 900Wh에서 800Wh로 줄어드는 상황을 기존 분기에서는 100kWh 소비로 해석할 수 있었다. SDK 입력은 Wh이므로 유효한 값은 크기에 관계없이 1,000으로 나눠 kWh로 변환하도록 수정했다.

[BatteryLevelTest](../app/src/test/java/com/nbbang/platform/BatteryLevelTest.kt)는 1,000Wh 경계와 0, 잘못된 입력을 확인한다. 1km 동안 900→800Wh로 줄어든 주행의 소비량은 0.1kWh, 실제 전비는 10km/kWh인지 검증한다. 1,001→999Wh로 경계를 지날 때도 소비량은 0.002kWh로 이어져야 한다.

변환은 [BatteryLevel](../app/src/main/java/com/nbbang/platform/BatteryLevel.kt)로 분리했고, 차량 신호를 받는 지점에서 호출한다. 최종 검증에서는 같은 앱 테스트 4개를 debug와 release 양쪽에서 실행했다.

## 구현과 검증을 나눈 방식

계산과 기록은 Android에 의존하지 않는 `core`에 두고, `app`은 SDK 신호·저장·화면을 연결한다. QR 청구서의 휴대폰 화면은 `web`에 둔다. 정산 규칙과 상태 변화는 단위 테스트로 확인하고, SDK에서 관찰한 환경별 차이는 [별도 기록](PLEOS_SDK.md)에 남겼다.

제출 APK를 만든 소스의 검증 결과는 core 155개와 app 4개, 서로 다른 테스트 총 159개 통과다. app 테스트를 두 빌드 변형에서 실행해 총 실행 수는 163회다. release 빌드와 서명 검증도 완료했다. 상세 명령·Lint 결과·APK SHA-256은 [빌드·검증 안내](BUILD.md)에 있다.

개발 중 에뮬레이터에서 확인한 내용, 단위 테스트로 확인한 로직, 실제 차량에서 확인하지 않은 범위를 구분한다. 특히 Gleo는 같은 형식의 broadcast로 시험했으며 실제 음성 인식부터 앱 호출까지의 연속 동작은 미검증이다. 최종 release APK를 새로 설치해 실행하는 검증도 이번 제출 빌드 점검에는 포함하지 않았다.
