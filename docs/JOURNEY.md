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

## 2026-10-08 17:20 - Touches are mapped from the letterboxed video rect to 800x480 phone coordinates. Back tap = Back on the phone, hold = leave projection; Menu or a top-right corner hold also leave. Settings (mic, Bluetooth, right-hand drive, night mode, nav bar) are plain widgets. MainActivity now runs a session through Pipe and opens the projection screen.

`ProjectionActivity` shows the picture and forwards touches (multi-touch, mapped through the letterbox rect) and keys. Back is tapped through to the phone, held for a second it leaves projection; Menu, or holding the top-right corner for 1.5 s, also leaves, for units with no Back key. `SettingsActivity` is plain widgets (no AppCompat on API 15). Changes apply to the next connection.

## 2026-10-08 19:10 - A real Android Auto build (17.9, on an Android 14 emulator image) served as the phone through its developer head-unit server. hu_client.py is a scripted head unit for poking it; relay.py lets a tablet reach the adb-forwarded port. Findings from the real phone: it hangs up about 5 s after TLS without AuthComplete, and it only keeps going if a mic service is announced or vehicle_type says 'motorcycle'.

First real Android Auto phone: Android Auto 17.9 on an Android 14 Google Play emulator image, with the developer option "Start head unit server" on :5277. `scripts/hu_client.py` is a scripted head unit used to see exactly what the phone asks for; `scripts/relay.py` is a time-boxed TCP relay so a tablet on the LAN can reach the adb-forwarded port.

Findings:

- The phone speaks AAP 1.7 and hangs up about 5 s after TLS if the head unit never sends AuthComplete.
- Android Auto 17.9 closes the connection (`startRequiredServices failed`) unless the service discovery response announces a microphone service, or sets `vehicle_type` to motorcycle (3), which makes the phone use its own mic. hu15 announces vehicle_type 3 unless the unit-mic option is on.
- Video is H.264 Constrained Baseline 800x480 level 3.1, exactly what OpenH264 handles; about 17 ms per frame on the ARM tablet.

Milestone: hu15 on the tablet against the real phone: video, touch and audio all work.

Tools: `scripts/emushot.sh` (host-side emulator screenshots), `scripts/mtouch.sh` (in-app touch through monkey; `sendevent` swipes only reach the status bar), `scripts/swipe.sh`.

## 2026-10-08 20:25 - Notes on what the target is and how apps get onto it, plus a read of OpenDroidAuto's USB and Honda integration to learn from.

Honda CONNECT, from public sources: NVIDIA Tegra (3), Android 4.0.4 with a Honda skin, 7" 800x480, small storage, Garmin navigation competing for RAM.

Apps can only be installed and run through the unit's lock-down: an installer whitelist (`allowed_installations.list`) and a runnable-process whitelist (`process_controls.list`), both edited by a community "Honda Permissions" app.

Prior art: OpenDroidAuto (GPL-3.0, C++/aasdk, runs over USB on Honda CONNECT per forum users, steering wheel buttons through Honda's steering service which needs `android.permission.ADA_SERVICE_ACCESS`, hardware decode through OMX). Read for its USB handling: AOA is done in Java (`UsbDeviceConnection.controlTransfer`), 10 s transfer timeouts, NUL-terminated strings. Its source is GPL-3.0, which can be combined into an AGPL-3.0 work.

## 2026-10-08 22:05 - AOA control requests 51 (get protocol), 52 (send strings) and 53 (start), then bulk transfers on the accessory interface through UsbDeviceConnection (API 12 calls only; the offset variant of bulkTransfer is API 18). The phone re-enumerates as 18d1:2d0x.

USB: `UsbTransport` sends the AOA handshake (51 GET_PROTOCOL, 52 SEND_STRING x6, 53 START), waits for the phone to re-enumerate as `18d1:2d0x`, claims the interface and reads/writes its two bulk endpoints. Only API 12 calls are used, so `bulkTransfer` has no offset form and writes go through a copy. 10 s bulk timeouts as OpenDroidAuto uses; a short timeout can make Linux drop data already received for that transfer. `MainActivity` gets "Connect USB phone" and "Phone hotspot", and reacts to `USB_DEVICE_ATTACHED`.

## 2026-10-08 23:45 - After a read error or EOF (not a bye-bye), retry the USB connect up to 5 times, 3 s apart; the counter resets after a one-minute session. If the unit relaunches the app with no live session, the projection screen bounces to MainActivity and reconnects. scripts/serve.py serves APKs to the unit's browser.

m4p. Link lost (read error or EOF, not a bye-bye from either side): retry the USB connect up to 5 times, 3 s apart, counter reset after a session that lasted a minute. The unit can relaunch an app in "last mode" with no live session; `ProjectionActivity` then hands over to `MainActivity` and reconnects. `scripts/serve.py` is a tiny upload/download HTTP server for moving APKs and logs to and from the unit's browser.

## 2026-10-09 09:05 - The unit has no file manager and no adb. 'Copy log to USB stick' writes hu15.log to the first writable removable mount under /storage or /mnt.

m4q. The first drive happened on m4p: audio fine; no skip buttons; the map video was heavily compressed during full-screen map updates; music and picture stopped after a couple of minutes. We could not get the log off the unit, which has no file picker, so there is now "Copy log to USB stick" (Advanced screen): it tries every removable-looking mount and writes `hu15-MMDD-HHMM.log` to the first one that takes it.

## 2026-10-09 11:20 - Skip buttons on the wheel did nothing while the on-screen ones worked, so the wheel keys do not reach the app as ordinary key events. Register a MEDIA_BUTTON receiver while projecting, log what arrives and forward it to the phone.

m4r. Wheel skip buttons did nothing while the on-screen ones worked. First attempt: handle `KEYCODE_MEDIA_*` in `ProjectionActivity`, and add a `MEDIA_BUTTON` broadcast receiver (registered with `AudioManager` while projecting) that logs every broadcast and forwards the key to the phone.

## 2026-10-09 14:20 - Messages from different channels interleave on the wire, but they were reassembled in one shared buffer, so a message that began on one channel and finished after another's fragment was dropped or mixed. Lost media frames never get acked, which leaks the phone's ack window until it paces itself to one frame per ~300 ms. Reassemble per channel. scripts/fps.py prints instantaneous fps from a log.

m4u. Car logs showed `unhandled media type 0xc on ch2`, odd message types on the video channel, and for minutes a rigid 7 frames per 2.1 s (3.3 fps). Cause: `Session.run` reassembled fragmented messages in one global buffer, but fragments of different channels interleave. Interleaved media was dropped or mixed, those frames were never acked, the phone's ack window leaked, and it paced itself to about one frame per 300 ms. Fix: one reassembly buffer and type per channel. `scripts/fps.py` prints the instantaneous fps from the `video:` log lines (the in-app figure is a running average and hides stalls).

## 2026-10-09 15:52 - The unit keeps the wheel buttons for itself unless an app binds its steering-menu service, registers a callback and announces that its menu is shown (as OpenDroidAuto does; AIDL files copied from it, GPL-3.0). They then arrive as ordinary key events with large negative codes. Needs android.permission.ADA_SERVICE_ACCESS.

m4v. Reading OpenDroidAuto's `HondaConnectManager`: wheel keys only arrive as key events after the app binds Honda's `ISteeringMenuService` (implicit intent, action = the AIDL interface name), registers a callback (`registerCallbackEx(cb, 260)`) and calls `notifySteeringMenuDispMode(260, 1)`. `WheelKeys` does that (AIDL copied from OpenDroidAuto) and logs everything under the tag `wheel`. Code table from OpenDroidAuto's keymap: -65535 plus, -65534 minus, -65533 right, -65532 left, -65531 source. `ProjectionActivity` maps left/right/source to previous/next/play-pause and, for now, plus/minus to d-pad up/down.

## 2026-10-09 16:00 - Touch felt unresponsive. Decode and texture upload were serialised under one lock, so touch handling and drawing waited on each other. The decoder now alternates two output buffers and the lock only guards the hand-over of the pending frame.

m4w. Touch coordinates and down/up pairs in the logs were clean; what felt unresponsive was latency. Decode (about 20-30 ms in software) and the GL texture upload ran one after the other under the same lock. `H264Decoder` now writes alternating output buffers; `Pipe` no longer holds the view lock around decode, and `YuvView`'s lock only covers handing over the pending frame.

## 2026-10-09 16:48 - connectUsb took the first non-hub device, which on the unit is the USB stick, so the phone was never tried. A phone left in accessory mode (18d1:2d00) from an earlier session also returned 'bad version response' or hung. Filter candidates (not hub, not accessory, no mass-storage interface) and try each; for a stale accessory, USBDEVFS_RESET via JNI, falling back to re-sending the AOA START, then wait for a NEW device name. Failure paths schedule a retry.

m4x. Reconnect got stuck "connecting" and never retried that USB device. Two causes in the log: (1) `connectUsb` used the first non-hub device, the USB stick (0781:5581), and never tried the phone (18d1:4ee1); (2) a stale accessory session (2d00) from the previous run answered `bad version response` or hung. Now: candidates are filtered (not a hub, not already an accessory, no mass-storage interface) and tried in turn until GET_PROTOCOL works; a stale accessory is reset (`USBDEVFS_RESET` through a new JNI function) and, if the reset fails, the AOA START is sent again so the phone re-enumerates; we wait for a new device name; and every failure path schedules a retry.

## 2026-10-09 18:20 - No Material library exists for API 15, so Ui builds cards and buttons from plain views. Pair connects and remembers the phone (its USB vendor id; API 15 gives no serial), or starts Android Auto if that phone is already connected. Advanced holds the debug tools, settings, the log and 'Forget paired phone'. SplashActivity is the launcher. The Android Auto mark is from Wikimedia Commons (public-domain artwork of a Google trademark).

m4z. Debug buttons moved behind an Advanced screen; the main menu is just "Pair" and "Advanced". Pair connects and remembers the phone by its vendor id (API 15 gives no serial); once paired and connected it launches Android Auto directly. Views are built by hand in `Ui` (colours, pressable cards, big buttons) since there is no Material library on API 15. `SplashActivity` shows the Android Auto mark (Wikimedia Commons, public domain, trademarked) and the app name for 1.3 s.

## 2026-10-10 10:15 - Binding the steering service also takes the volume buttons, and forwarding them to the phone as d-pad up/down meant the app took over volume. Call the unit's own volume command (ModeMgrService.reqModeMgrSteeringVolCmd by reflection, as OpenDroidAuto does), falling back to AudioManager. Volume keys repeat while held.

m5a. First drive on the new UI: it looked right, phone connected perfectly, prev/next worked, but wheel plus/minus were forwarded as d-pad up/down so the app took over volume. The binding makes those keys ours, so we hand them back: `getSystemService("ModeMgrService").reqModeMgrSteeringVolCmd(boolean)` by reflection (the call OpenDroidAuto makes), with `AudioManager.adjustStreamVolume` as the fallback. Also noted on that drive: the screen was disabled by the OS while driving, probably a property of the unit's permissions entry for the app.

## 2026-10-10 11:35 - The voice button arrives as a negative code (-65522, seen once on the last drive); Android Auto starts the assistant on KEYCODE_SEARCH. The screen blanking while driving was the unit's App type: NAVI avoids it. A 21-minute drive ran with no drops at about 29 fps. Adds the permissions entry that works and the app screenshots.

m5b/m5c. Screen blanking while driving: the process-control entry's App type has to be NAVI, otherwise the unit blanks the screen under its driving restriction. Confirmed on the next drive. That drive also ran 21 minutes with no drops: about 29.4 fps, decode average 17.9 ms, ping round trip 25 ms. Code -65522 appeared once when the voice button was pressed, so it is now mapped to `KEYCODE_SEARCH` (what Android Auto uses to start the assistant). See `docs/honda-permissions.md`.

## 2026-10-11 08:05 - After a hu15 session the unit's other audio apps stop working ('Audio power off') until a reboot. Reading OpenDroidAuto: apps with the THIRD_PARTY type use stream constant 1 and make no ModeMgr calls, while PREINSTALL apps use Honda streams 11 (media) and 15 (speech, system) and tell ModeMgr when audio starts and stops. Settings > Audio output chooses Music, System (default) or Honda streams with the ModeMgr handoff. Also request and abandon standard audio focus around a session, and pause/flush a track before releasing it.

m5d. After using hu15 the unit's own audio apps stay dead with "Audio power off" until a reboot. A suggestion to change the sample rate does not fit: media already plays at 48 kHz stereo. Reading OpenDroidAuto's `HondaConnectManager`: a THIRD_PARTY app "already has audio focus" and plays on `AudioTrack.MODE_STREAM` (the constant 1, i.e. `STREAM_SYSTEM`); a PREINSTALL app plays on Honda streams 11 (media) and 15 (speech, system) and calls ModeMgr: `sendModeMgrOnReq`, `sendModeMgrOnCnf`, `notifyModeMgrStatus` (audio index 213, values 31/7/3) at start and `sendModeMgrOffReq`, `sendModeMgrOffCnf` at the end. New setting Audio output: Music stream, System stream (default) or Honda streams + handoff (`HondaAudio`, by reflection, needs PREINSTALL). Unverified until it is driven.

## 2026-10-11 10:30 - With the Honda handoff the 'Audio power off' state is gone (every ModeMgr call returned 0). 'ok Google' then froze the link: the phone's microphone request arrived, nothing more was read, and no 'mic open' or error was logged. Opening the AudioRecord blocked the thread that reads from the phone. Do it on a worker thread so a stuck mic cannot stall the session.

m5e. m5d log: the ModeMgr handoff works (all five calls return 0), no "Audio power off", other audio apps fine. "ok Google" then froze the session (unit microphone option was on): `voice session 1`, the phone sent its microphone request on channel 7, and from then on the read loop read nothing, pings went unanswered, and neither "mic open" nor an error was logged, so `new AudioRecord` / `startRecording` was blocking the read loop thread. The mic now opens on its own thread, so a stuck mic cannot take the link with it. With the unit mic option off, the phone uses its own mic and "ok Google" had already worked.

## 2026-10-11 11:45 - After the freeze the log kept saying 'no ping reply' for over 36 s and the session never ended, and replugging resumed the frozen Android Auto on the phone. Past 15 s, send bye-bye so the phone drops its session, end ours and let the app reconnect.

m5f. Replugging after the freeze resumed the frozen Android Auto session on the phone, because our side never ended it: the log said "no ping reply for 36 s" and nothing else. The pinger now ends the session after 15 s without a reply: it sets `linkLost` (so the app reconnects by itself), sends bye-bye so the phone drops its end, and closes the link; the ModeMgr handoff is released by the normal session-end path. If the USB link itself is stuck the bye-bye may not arrive and the phone may need Android Auto force-stopped.
