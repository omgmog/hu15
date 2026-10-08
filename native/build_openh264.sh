#!/bin/bash
# Builds libopenh264.a (static) for API 15 with NDK r17c, the last NDK that targets API < 16.
# Usage: native/build.sh arm|x86      Output: native/out/<abi>/libopenh264.a
set -e
ARCH=${1:?arm|x86}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
N=$ROOT/android-sdk/android-ndk-r17c
LLVM=$N/toolchains/llvm/prebuilt/linux-x86_64
export LD_LIBRARY_PATH=$ROOT/native/lib   # libtinfo.so.5 shim: Debian 12 only ships .so.6
case $ARCH in
  arm) TRIPLE=arm-linux-androideabi; TARGET=armv7-none-linux-androideabi; ABI=armeabi-v7a; INC=arm-linux-androideabi; ASM=Yes
       GCCTC=$N/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64
       # no -mfpu here: openh264's own makefile picks vfpv3-d16 and its NEON .S files enable neon per file
       CPUFLAGS="-march=armv7-a -mfloat-abi=softfp" ;;
  x86) TRIPLE=i686-linux-android; TARGET=i686-none-linux-android; ABI=x86; INC=i686-linux-android; ASM=No   # no nasm here; emulator-only build
       GCCTC=$N/toolchains/x86-4.9/prebuilt/linux-x86_64
       CPUFLAGS="-mstackrealign -msse2"   # ICS Dalvik calls JNI with a 4-byte aligned stack on x86; clang assumes 16 ;;
esac
TC=$ROOT/native/tc/$ARCH; mkdir -p $TC
COMMON="-target $TARGET -gcc-toolchain $GCCTC -D__ANDROID_API__=15 -isystem $N/sysroot/usr/include/$INC -isystem $N/sysroot/usr/include --sysroot=$N/platforms/android-15/arch-$ARCH $CPUFLAGS"
LIBCXX="-nostdinc++ -isystem $N/sources/cxx-stl/llvm-libc++/include -isystem $N/sources/android/support/include -isystem $N/sources/cxx-stl/llvm-libc++abi/include"
LINKLIBS="-L$N/sources/cxx-stl/llvm-libc++/libs/$ABI -lc++_static -lc++abi -landroid_support -lunwind"
[ $ARCH = x86 ] && LINKLIBS="-L$N/sources/cxx-stl/llvm-libc++/libs/$ABI -lc++_static -lc++abi -landroid_support"
cat > $TC/cc <<W
#!/bin/bash
export LD_LIBRARY_PATH=$ROOT/native/lib
exec $LLVM/bin/clang $COMMON "\$@"
W
cat > $TC/cxx <<W
#!/bin/bash
export LD_LIBRARY_PATH=$ROOT/native/lib
for a in "\$@"; do [ "\$a" = -c ] && exec $LLVM/bin/clang++ $COMMON $LIBCXX "\$@"; done
exec $LLVM/bin/clang++ $COMMON $LIBCXX "\$@" $LINKLIBS
W
chmod +x $TC/cc $TC/cxx
OUT=$ROOT/native/out/$ABI; mkdir -p $OUT
SRC=$ROOT/native-src/openh264
cd $SRC
make clean >/dev/null 2>&1 || true
make -j2 OS=android ARCH=$ARCH TARGET=android-15 NDKLEVEL=15 NDKROOT=$N USE_ASM=$ASM BUILDTYPE=Release \
     CC=$TC/cc CXX=$TC/cxx AR=$LLVM/bin/llvm-ar libopenh264.a
cp libopenh264.a $OUT/
ls -la $OUT
