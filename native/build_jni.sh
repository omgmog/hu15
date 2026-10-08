#!/bin/bash
# Builds libhu15n.so (JNI + static OpenH264) for API 15 into hu15/app/src/main/jniLibs/<abi>/. Run build_openh264.sh <arch> first.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
for ARCH in "$@"; do
  ABI=armeabi-v7a; [ $ARCH = x86 ] && ABI=x86
  DST=$ROOT/hu15/app/src/main/jniLibs/$ABI; mkdir -p $DST
  $ROOT/native/tc/$ARCH/cxx -O2 -fPIC -shared -fno-rtti -fno-exceptions -fvisibility=hidden -I$ROOT/native-src/openh264/codec/api/wels \
     $ROOT/native/jni/hu15n.cpp $ROOT/native/out/$ABI/libopenh264.a -llog -Wl,--exclude-libs,ALL -Wl,-z,relro -Wl,--no-undefined \
     -o $DST/libhu15n.so
  ls -la $DST/libhu15n.so
done
