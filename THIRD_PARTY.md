# Licensing

Everything written here is **AGPL-3.0-or-later**, see LICENSE.
The protocol knowledge (message types, field numbers, framing) comes from HeadUnit Revived (andreknieriem/open-headunit, AGPL-3.0, Mike Reid lineage). If you give anyone the APK you must offer them this source.

The embedded reference certificate and key (hu15/app/src/main/res/raw/cert, privkey) are HeadUnit Revived's.

Bundled / linked:
- Conscrypt 2.5.3 (Google), Apache-2.0, TLS 1.2 provider (platform TLS on API 15 stops at TLS 1.0).
- OpenH264 v2.4.1 (Cisco), BSD-2-Clause, statically linked into libhu15n.so (source expected in native-src/openh264, built by native/build_openh264.sh).
  Cisco's patent licence covers only Cisco's own binaries; a self-built H.264 decoder has no patent cover. Personal use here; check before distributing.
- NDK r17c libc++ (Apache-2.0 with LLVM exception).
- OpenDroidAuto (MaKi983, GPL-3.0): the two AIDL interface files for Honda's steering-menu service (hu15/app/src/main/aidl/...) are copied from it. GPL-3.0 code can be combined into an AGPL-3.0 work.
Not used: HeadUnit Revived's code, gartnera/headunit (AGPL C++), FFmpeg.
