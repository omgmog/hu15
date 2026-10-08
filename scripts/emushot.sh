#!/bin/bash
# usage: emushot.sh out.png - host-side screenshot of the running emulator (adb screencap is blank with host-GPU emulation)
D=$(cd "$(dirname "$0")/.." && pwd); T=$(mktemp -d)
timeout 30 "$D/android-sdk/platform-tools/adb" emu screenrecord screenshot "$T" >/dev/null 2>&1 && cp "$T"/*.png "$1"; rm -rf "$T"
