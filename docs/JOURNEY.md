# Journey

A dated log of what was tried, found and decided, in order. Read it next to `git log`.

## 2026-10-07 21:35 - open-headunit is the nearest Android Auto head-unit app. Checked what it needs against API 15: MediaCodec, AppCompat/Material UI and platform TLS 1.2 are all missing there. Decided on a small new app that reuses the protocol knowledge, not a patched-down copy.

Goal: an Android Auto head-unit receiver for Honda CONNECT units: Android 4.0.4 (API 15), 800x480, Tegra 3, no app store; apps are installed through the unit's own whitelist tooling.

Audit of open-headunit, the closest existing head-unit app, against API 15:

- No `MediaCodec` (arrived in API 16), so H.264 has to be decoded in software.
- No AppCompat/Material: any UI has to be plain views.
- Platform TLS stops at TLS 1.0; Android Auto needs TLS 1.2, so we need a bundled provider (Conscrypt).
- NDK r17c is the last NDK that can target API < 16.

Decision: don't patch open-headunit down. Write a thin app in plain Java, reuse the protocol knowledge (framing, message types, field numbers) from HeadUnit Revived, decode with Cisco OpenH264 (the phone sends H.264 Constrained Baseline, which it handles) through JNI, draw with a GLES2 YUV shader, and run TLS 1.2 through a Conscrypt `SSLEngine`.

## 2026-10-07 22:20 - Smallest possible app: reports SDK_INT, ABI, display, GL ES version, supported TLS protocols, USB host and accessory features, and whether Conscrypt loads. Also notes how the x86 API 15 emulator is run (KVM, no GPU).

Milestone 0: `probe/`, a throwaway APK that reports SDK_INT, ABI, display size and density, GL ES version, the TLS protocols the platform offers, USB host/accessory features, and whether Conscrypt's native library loads.

Emulator: an x86 API 15 AVD under KVM. There is no usable GPU, and `adb screencap` is blank with host-GPU emulation, so screenshots come from the emulator console instead.

## 2026-10-07 23:05 - minSdk 15, plain Java. Transport is the seam between a byte pipe and the AAP link (TCP first). Pb is a tiny hand-rolled protobuf reader/writer instead of a library. Prefs holds the settings; Log15 logs to logcat and to a file.

Milestone 1: the `hu15/` Gradle project. `Transport` is the one interface between a byte pipe and the AAP link; `TcpTransport` is the first implementation (fake phone, Wi-Fi tablets). `Pb` is a hand-rolled protobuf reader/writer (the messages are small and the field numbers come from HeadUnit Revived's .proto files). `Log15` writes to logcat and to `hu15.log`, because the target has no adb and the file is the product.

## 2026-10-07 23:50 - Frame format [chan][flags][len][type], version exchange, and the TLS client handshake tunnelled in type-3 frames through a Conscrypt SSLEngine, using HeadUnit Revived's reference certificate and key. TLS records must reach the wire in encryption order, so wrap and write share a lock.

AAP link: frames are `[chan][flags][len][type]`; the version exchange comes first; the TLS handshake is carried in type-3 frames and run by a Conscrypt `SSLEngine` as the client, with the reference certificate and key from HeadUnit Revived. The head unit sends AuthComplete once TLS is up.

Bug found while testing: TLS records must hit the wire in the order they were encrypted, or the peer reports a bad record MAC. Wrap and write now share one lock (touch, acks and mic will all send from different threads).

## 2026-10-08 10:40 - Session answers the phone's control traffic, opens the announced channels, acks media and hands payloads to a Sink. scripts/fake_phone.py plays a phone so the protocol can be tested on the emulator with no phone. MainActivity is a debug screen: self-test and a TCP connect with a logging sink.

Milestones 2-3a. `Session` answers version/auth, service discovery, channel open, sensors, input binding, pings, nav and audio focus, and acks media. `scripts/fake_phone.py` plays a phone: service discovery, six channel opens, sensor/input/ping/focus traffic and an 800x480 H.264 test stream. `MainActivity` is a debug screen with a TLS self-test and a TCP connect that logs what arrives.

Verified on the API 15 x86 emulator: version exchange, TLS 1.2 via Conscrypt, discovery, channel opens, sensor start, and acked media.

## 2026-10-08 13:55 - OpenH264 is built statically with NDK r17c for armeabi-v7a (NEON) and x86 (no asm) and called through a small JNI layer that returns I420 in a direct ByteBuffer. A GLES2 shader converts YUV to RGB, letterboxed to the 800x480 aspect. check_api15_syms.sh lists any symbol the .so imports that API 15's libc does not have. OpenH264's source is not vendored; the built libraries are committed so the app builds without an NDK.

Milestone 3b: video. OpenH264 v2.4.1 is built as a static library with NDK r17c (`native/build_openh264.sh`), linked into `libhu15n.so` with a small JNI layer (`native/jni/hu15n.cpp`) that decodes into a direct `ByteBuffer`. `YuvView` uploads the three planes as luminance textures and a fragment shader does the YUV to RGB conversion, letterboxed so the 800x480 picture is never stretched. `scripts/check_api15_syms.sh` checks that the library imports nothing API 15's libc lacks.

x86 has no assembler here (no nasm), so that build is emulator-only; ICS Dalvik also calls JNI with a 4-byte aligned stack on x86, hence `-mstackrealign`.

## 2026-10-08 15:35 - Pipe is the Session sink: it decodes video under the view's lock so GL never uploads a half-written picture, and starts one PcmPlayer per audio channel. PcmPlayer feeds an AudioTrack from a bounded queue so a slow audio sink can never stall the session's read loop.

`Pipe` implements `Session.Sink`: video goes through the OpenH264 decoder into the attached `YuvView`; each audio channel gets a `PcmPlayer`, an `AudioTrack` fed from a bounded queue (about 2.4 s; the oldest chunk is dropped when full) so a slow sink cannot stall the read loop. Decode runs under the view's lock so GL cannot upload a picture that is still being written.
