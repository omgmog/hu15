#!/bin/sh
# Touch injection for the API 15 emulator via `monkey` raw-event scripts (sendevent touches don't reach app windows reliably on this image).
# Usage: scripts/mtouch.sh x1 y1 [x2 y2]    tap, or drag from (x1,y1) to (x2,y2) in 6 steps. Pixels, 800x480.
D=$(cd "$(dirname "$0")/.." && pwd)
x1=$1 y1=$2 x2=${3:-$1} y2=${4:-$2}
T=$(mktemp)
echo "type= raw events
count= 10
speed= 1.0
start data >>" > $T
t=1000
ev() { echo "DispatchPointer($t,$t,$1,$2,$3,1.0,1.0,0,1.0,1.0,0,0)" >> $T; t=$((t+20)); }
ev 0 $x1 $y1
i=1
while [ $i -le 6 ]; do ev 2 $((x1+(x2-x1)*i/6)) $((y1+(y2-y1)*i/6)); i=$((i+1)); done
ev 1 $x2 $y2
A="$D/android-sdk/platform-tools/adb"
$A push $T /data/local/tmp/mt.txt >/dev/null && timeout 30 $A shell monkey -f /data/local/tmp/mt.txt 1 2>&1 | tail -2
