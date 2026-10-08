#!/bin/bash
# Lists undefined libc/libm/libdl symbols of an armv7 .so that the API 14/15 stub libs (NDK r17c) do not export -> dlopen would fail on 4.0.x.
# Usage: scripts/check_api15_syms.sh path/to/lib.so [api=14]
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RE=$(ls $ROOT/android-sdk/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf | head -1)
L=$ROOT/android-sdk/android-ndk-r17c/platforms/android-${2:-14}/arch-arm/usr/lib
for lib in libc libm libdl liblog libstdc++; do [ -f $L/$lib.so ] && $RE --dyn-syms $L/$lib.so | awk '$7!="UND"{print $8}' | sed 's/@.*//'; done | sort -u > /tmp/api_syms.$$
echo "NEEDED:"; $RE -d "$1" | rg NEEDED
echo "missing on API ${2:-14} (weak symbols are fine):"
$RE --dyn-syms "$1" | awk '$7=="UND"&&$8!=""{print $5, $8}' | sed 's/@.*//' | sort -u | while read b s; do grep -qx "$s" /tmp/api_syms.$$ || echo "  $b $s"; done
rm -f /tmp/api_syms.$$
