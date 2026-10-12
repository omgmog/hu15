# hu15

Android Auto receiver for Android 4.0.4 (API 15) head units, built for the Honda CONNECT unit in a 2018 HR-V. Software H.264 video, TLS 1.2 via Conscrypt, USB or Wi-Fi.

It runs where there is no MediaCodec, no AppCompat and TLS only goes up to 1.0 (Tegra 3, 800x480), and has run a 21-minute session at about 29 fps with no drops.

- **Video:** OpenH264 software decode (JNI) to a GLES2 YUV shader.
- **Transport:** USB Android Open Accessory, or TCP (fake phone, Wi-Fi).
- **TLS:** Conscrypt `SSLEngine` for TLS 1.2.
- **Audio and voice:** PCM `AudioTrack`s; optional unit microphone.
- **Honda:** steering-wheel keys, volume through the unit's ModeMgr, and an audio handoff for the PREINSTALL permission type.

## Layout
- `hu15/` - the Android app. `./gradlew :app:assembleDebug` with `sdk.dir` set in `hu15/local.properties`.
- `native/` - JNI glue and build scripts. OpenH264 v2.4.1 source is not vendored: `native/build_openh264.sh` expects it in `native-src/openh264` and an NDK r17c in `android-sdk/android-ndk-r17c`. Prebuilt `libhu15n.so` files are committed under `hu15/app/src/main/jniLibs/`.
- `scripts/` - fake phone, emulator screenshots and touch, log helpers, a small HTTP server.
- `probe/` - the first throwaway APK that tested what the unit allows.
- `docs/` - `JOURNEY.md` (dated log, read next to `git log`), `honda-permissions.md`, `keycodes.md`, `screens/`.

## Licence
AGPL-3.0-or-later (see `LICENSE` and `THIRD_PARTY.md`). If you give anyone the APK you must offer them this source.
