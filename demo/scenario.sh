#!/usr/bin/env bash
# 29미터 시연 시나리오 — Pleos Connect Emulator v2.1.0 + Virtual Console REST API
# 준비: 에뮬레이터 Cold boot, 앱 설치(adb install -r -g), Virtual Console 브라우저 탭은 닫아 둠(값을 덮어쓸 수 있음)
# 실행: bash demo/scenario.sh          (adb가 PATH에 없으면 ADB=/경로/adb bash demo/scenario.sh)
set -u
ADB="${ADB:-adb}"
VC=http://127.0.0.1:8088
"$ADB" forward tcp:8088 tcp:8088 >/dev/null

# Virtual Console은 하트비트가 없으면 503을 돌려준다
( while true; do curl -s -o /dev/null -X POST "$VC/browser/session?client=m29-demo"; sleep 1; done ) &
HB=$!
trap 'kill $HB 2>/dev/null' EXIT

put() { for t in 1 2 3; do [ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$VC/vehicle/$1")" = 200 ] && return; sleep 0.3; done; echo "실패: $1"; }
geo() { "$ADB" emu geo fix "$1" "$2" >/dev/null; }   # 경도 위도
say() { echo "[$(date +%T)] $*"; }

# 북쪽으로 이동(위도 증가) / 동쪽으로 이동(경도 증가)
drive_north() { local lon=$1 lat=$2 n=$3 step=$4 i; for ((i = 0; i < n; i++)); do geo "$lon" "$(echo "$lat + $i * $step" | bc -l)"; sleep 1; done; }
drive_east()  { local lon=$1 lat=$2 n=$3 step=$4 i; for ((i = 0; i < n; i++)); do geo "$(echo "$lon + $i * $step" | bc -l)" "$lat"; sleep 1; done; }

sleep 2
say "초기화: P단, 전조등 꺼짐, 와이퍼 꺼짐, 0km/h"
put "gear?gear=Park"; put "lights?feature=high_beam&value=false"; put "lights?feature=headlamp_mode&value=Off"
put "body?front_wiper=Off"; put "controls?speed=0&accelerator=0&brake=0"

say "장면 1 — 겨울 퇴근길, 강원 철원군 동송읍 보행자 사고 다발지역(도로교통공단 데이터)으로 접근"
geo 127.218579 38.2052
"$ADB" shell am start -n kr.meter29/.MainActivity >/dev/null
sleep 6
put "lights?feature=headlamp_mode&value=LowBeam"; put "gear?gear=Drive"
for s in 30 50 70; do put "controls?speed=$s&accelerator=40&brake=0"; sleep 1; done
drive_north 127.218579 38.2056 16 0.00012       # 하향등 70km/h → 300m 안에서 경고(조명 호박색 + 음성 1회), 음성이 끝날 때까지 계속 접근
say "상향등 켬 → 보이는 거리 79m > 멈추는 거리 47m → 경고 해제, 조명 원래 색"
put "lights?feature=high_beam&value=true"
drive_north 127.218579 38.2075 6 0.00012
sleep 3
put "controls?speed=0&accelerator=0&brake=60"; sleep 1      # 정차한 뒤 상향등을 끈다(지점 앞에서 끄면 다시 경고)
put "lights?feature=high_beam&value=false"

sleep 6
say "장면 2 — 비 오는 밤, 충남 태안군 백화로 집중조명이 없는 횡단보도(전국횡단보도표준데이터)로 접근"
geo 126.3077 36.755341; sleep 2
put "body?front_wiper=Low"
for s in 30 50; do put "controls?speed=$s&accelerator=30&brake=0"; sleep 1; done
drive_east 126.3080 36.755341 16 0.00015       # 와이퍼 작동 50km/h → 젖은 노면 38m > 29m → 경고, 음성이 끝날 때까지 계속 접근
say "감속 30km/h → 경고 해제"
put "controls?speed=30&accelerator=0&brake=30"
drive_east 126.31025 36.755341 6 0.0001
sleep 3

say "장면 3 — 도착, P단: 오늘 밤 보이는 거리보다 빨랐던 곳"
put "controls?speed=0&accelerator=0&brake=100"; sleep 2
put "gear?gear=Park"; put "body?front_wiper=Off"
sleep 12
say "끝"
