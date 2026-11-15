#!/usr/bin/env bash
# N빵~ 시연 도구. Pleos 에뮬레이터 + 가상 콘솔(HTTP API)로 함께 탄 길을 흉내 낸다.
#
#   tools/demo.sh setup          가상 콘솔 연결, 권한, 차 초기화(P, 배터리 70kWh. 주행거리는 이어 간다)
#   tools/demo.sh seed           시연용 카풀 그룹("판교 출근")과 지난 20일 출근 장부, 지난달 청구(1건 미납) 넣기
#   tools/demo.sh reset          장부 비우기
#   tools/demo.sh payee          받을 곳(예시 계좌) 넣기, 연료를 전기로(가상 콘솔 배터리로 실제 연비를 잰다)
#   tools/demo.sh fuel <연료>     연료 종류만 바꾸기: 휘발유, 경유, LPG, 전기
#   tools/demo.sh model <차종>    C-02 차종: avante(2027 아반떼), grandeur(2027 그랜저), none(직접 입력). 기름차면 공인연비 기준연비를 쓴다
#   tools/demo.sh economy <km/L>  차가 알려 주는 평균 연비(가상 콘솔에 없다). 실제 연비로 쓴다. setup이 16.2로 넣는다
#   tools/demo.sh official [휘발유원]   오피넷 키 없이 공시 평균 유가 화면 보기(오늘 날짜, 기본 1667원)
#   tools/demo.sh travel <이름,이름>   T-01 여행 시작(P에서). 좌석 센서는 쓰지 않는다
#   tools/demo.sh board <이름,이름>    C-04 탑승자 추가(주행 중에도)
#   tools/demo.sh alight <이름>        C-05 하차
#   tools/demo.sh door <문> [open|close]  문: driver, passenger, rear_left, rear_right
#   tools/demo.sh gear P|D
#   tools/demo.sh drive <km> [km/h]  주행거리 km만큼 늘리고 배터리를 km÷5.8 kWh만큼 줄인다(속도 기본 60km/h, 차속은 보내지 않는다)
#                                앱 시계를 km÷속도 시간만큼 흘린다. 앱은 1km마다 주행거리÷시간이 56km/h를 넘으면 고속, 아니면 도심으로 센다
#   tools/demo.sh route <이름> <통행료>   내비 경로(에뮬레이터 내비 대신)
#   tools/demo.sh toll | arrive  톨게이트 지남 / 도착
#   tools/demo.sh at <주소>       지금 주소(에뮬레이터 내비 대신)
#   tools/demo.sh gleo settle [이름] | treat | cost <금액> [PARKING|TOLL|OTHER]
#   tools/demo.sh trip           친구 여행 시나리오 전체(셋 출발, 도심 40km/h로 한 명 하차, 톨게이트 뒤 고속 100km/h, 도착)
#
# 가상 콘솔은 1초마다 /browser/session 신호가 있어야 값을 차에 보낸다. 명령마다 몇 초 보내고 끝낸다.
set -u
# WSL에서 Windows 에뮬레이터를 쓰면: ADB=<SDK 경로>/platform-tools/adb.exe CURL=curl.exe tools/demo.sh ...
A=${ADB:-adb}
CURL=${CURL:-curl}
V=http://127.0.0.1:8088
PKG=com.nbbang
STATE=/tmp/nbbang-demo-state
mkdir -p "$STATE"

beat() { "$CURL" -s -m 2 -X POST "$V/browser/session?client=nbbang-demo" >/dev/null 2>&1; }
post() { beat; "$CURL" -s -m 5 -X POST "$V/vehicle/$1" >/dev/null; }
# adb shell은 인자를 한 줄로 이어 붙이므로 띄어쓰기가 있는 값("가평 펜션")은 작은따옴표로 감싼다.
demo() {
  local action=$1 args="" a
  shift
  for a in "$@"; do args+=" '$a'"; done
  "$A" shell "am broadcast --user 10 -n $PKG/.debug.DemoReceiver -a $PKG.demo.$action$args" >/dev/null
}
gleo() {
  if [ -n "${2:-}" ]; then
    "$A" shell "am broadcast --user 10 -n $PKG/.platform.GleoActionReceiver -a $1 --es ACTION_PARAM '$2'" >/dev/null
  else
    "$A" shell am broadcast --user 10 -n $PKG/.platform.GleoActionReceiver -a "$1" >/dev/null
  fi
}
hold() { for _ in $(seq 1 "${1:-2}"); do beat; sleep 1; done; }
km_now() { cat "$STATE/km" 2>/dev/null || echo 1203; }
kwh_now() { cat "$STATE/kwh" 2>/dev/null || echo 70.0; }

put() { beat; "$CURL" -s -m 5 -X PUT "$V/vehicle/$1" >/dev/null; }
# 가상 콘솔 문 이름: 운전석 fl, 조수석 fr, 뒷좌석 rl·rr. 잠금을 풀어야 열린다.
door() {
  local key
  case "$1" in driver) key=fl ;; passenger) key=fr ;; rear_left) key=rl ;; rear_right) key=rr ;; *) key=$1 ;; esac
  put "body?doors_locked=0&door_$key=$([ "${2:-open}" = open ] && echo 1 || echo 0)"
}

# 가상 콘솔로 차속(controls?speed=)을 보내지 않는다. swiftshader 에뮬레이터는 계기판 3D 주행 화면이 차속을 받아 움직이면
# 1분 안에 오류 기록 없이 꺼진다(11/15 확인). 앱은 차속을 쓰지 않는다(도심·고속은 주행거리 ÷ 시간).
drive() {
  local km=$1 speed=${2:-60} now kwh
  now=$(km_now); kwh=$(kwh_now)
  now=$(python3 -c "print(round($now + $km, 1))")
  kwh=$(python3 -c "print(round($kwh - $km / 5.8, 2))")
  echo "$now" > "$STATE/km"; echo "$kwh" > "$STATE/kwh"
  # 앱은 도심·고속을 주행거리 ÷ 걸린 시간으로 나눈다. 가상 콘솔은 주행거리를 한 번에 올리니 그 시간을 앱 시계에 먼저 흘린다.
  demo ELAPSE --el ms "$(python3 -c "print(int($km / $speed * 3600000))")"
  post "odometer?traveled_distance_km=$now"
  post "ev-battery?field=current_energy&value=$kwh"
  echo "주행거리 ${now}km, 배터리 ${kwh}kWh"
}

case "${1:-}" in
  setup)
    "$A" forward tcp:8088 tcp:8088 >/dev/null
    "$A" shell appops set --user 10 $PKG SYSTEM_ALERT_WINDOW allow
    "$A" shell pm grant --user 10 $PKG pleos.car.permission.FUSED_LOCATION 2>/dev/null
    demo ECONOMY --ef kmPerLiter "${ECONOMY:-16.2}"
    # 주행거리계는 줄어들지 않는다(낮은 값은 무시된다). 지난번 값에서 이어 간다. 배터리는 충전한 셈 치고 70kWh로 채운다.
    km=$(km_now); echo "$km" > "$STATE/km"; echo 70.0 > "$STATE/kwh"
    post "gear?gear=Park"
    post "odometer?traveled_distance_km=$km"; post "ev-battery?field=current_energy&value=70.0"
    hold 2; echo "준비: P, ${km}km, 70kWh" ;;
  seed) demo SEED ;;
  reset) demo RESET ;;
  payee) demo PAYEE --es name 운전자 --es account "예시은행 000-0000-0000" --es fuel 전기 ;;
  fuel) demo PAYEE --es name 운전자 --es account "예시은행 000-0000-0000" --es fuel "$2" ;;
  official) demo OFFICIAL --ei gasoline "${2:-1667}" ;;
  model)
    case "${2:-}" in avante) m=AVANTE_2027 ;; grandeur) m=GRANDEUR_2027 ;; *) m=none ;; esac
    demo PAYEE --es name 운전자 --es account "예시은행 000-0000-0000" --es model "$m" ;;
  economy) demo ECONOMY --ef kmPerLiter "${2:-0}" ;;
  travel) demo TRAVEL --es names "$2"; hold 2 ;;
  board) demo BOARD --es names "$2"; hold 1 ;;
  alight) demo ALIGHT --es name "$2"; hold 1 ;;
  door) door "$2" "${3:-open}"; hold 2 ;;
  gear) post "gear?gear=$([ "$2" = D ] && echo Drive || echo Park)"; hold 2 ;;
  drive) drive "$2" "${3:-60}"; hold 2 ;;
  route) demo ROUTE --es name "$2" --ei toll "${3:-0}" ;;
  toll) demo TOLLGATE ;;
  arrive) demo ARRIVE ;;
  at) demo ADDRESS --es address "$2" ;;
  gleo)
    case "${2:-}" in
      settle) if [ -n "${3:-}" ]; then gleo SETTLE_RIDE "{\"person\":\"$3\"}"; else gleo SETTLE_RIDE; fi ;;
      treat) gleo TREAT_RIDE ;;
      cost) gleo ADD_RIDE_COST "{\"amount\":${3:-3000},\"kind\":\"${4:-PARKING}\"}" ;;
    esac ;;
  trip)
    "$0" setup
    demo ADDRESS --es address "왕십리역"
    demo TRAVEL --es names "${NAMES:-민지,지훈,수아}"; hold 2
    demo ROUTE --es name "가평 펜션" --ei toll 3800
    "$0" gear D
    # 왕십리 → 잠실은 시내(도심), 잠실 → 가평은 고속도로(고속). 앱은 1km마다 평균 차속으로 나눈다.
    for _ in 1 2 3; do drive 10 40; hold 1; done
    demo ADDRESS --es address "잠실역"
    "$0" gear P; door rear_right open; demo ALIGHT --es name "${LEAVER:-수아}"; hold 2; door rear_right close
    "$0" gear D
    for _ in 1 2; do drive 10 100; hold 1; done
    demo TOLLGATE
    for _ in 1 2 3 4; do drive 10 100; hold 1; done
    demo ADDRESS --es address "가평 펜션"
    demo ARRIVE; "$0" gear P; hold 3
    echo "도착: 주행 요약(T-03)이 떠야 해요. N빵하기! → C-08(절약 기여면 C-11 먼저) → 게임 → C-17 → C-18. 문을 열어도 주행은 끝나지 않아요." ;;
  *) sed -n '2,23p' "$0"; exit 1 ;;
esac
