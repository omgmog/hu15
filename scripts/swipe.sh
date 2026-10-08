#!/bin/sh
# Swipe on the API 15 emulator (ICS `input` has no swipe/tap). Usage: scripts/swipe.sh x1 y1 x2 y2 [steps=12]   (pixels, 800x480)
# Touch is event1 (qwerty2), multitouch protocol A: ABS_MT_TRACKING_ID(57), POSITION_X/Y(53/54), PRESSURE(58), BTN_TOUCH(330), SYN_MT_REPORT(0 2 0).
D=$(cd "$(dirname "$0")/.." && pwd)
x1=$1 y1=$2 x2=$3 y2=$4 n=${5:-12}
sc() { echo $(( $1 * 32767 / $2 )); }
cmd="E=/dev/input/event1; s() { sendevent \$E \$1 \$2 \$3; }
s 3 57 0; s 3 53 $(sc $x1 800); s 3 54 $(sc $y1 480); s 3 58 100; s 1 330 1; s 0 2 0; s 0 0 0;"
i=1
while [ $i -le $n ]; do
  x=$(( x1 + (x2 - x1) * i / n )); y=$(( y1 + (y2 - y1) * i / n ))
  cmd="$cmd s 3 57 0; s 3 53 $(sc $x 800); s 3 54 $(sc $y 480); s 3 58 100; s 0 2 0; s 0 0 0;"
  i=$(( i + 1 ))
done
cmd="$cmd s 1 330 0; s 0 2 0; s 0 0 0"
timeout 20 "$D/android-sdk/platform-tools/adb" shell "$cmd"
