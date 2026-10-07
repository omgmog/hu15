# Licensing

Everything written here is **AGPL-3.0-or-later**, see LICENSE.
The protocol knowledge (message types, field numbers, framing) comes from HeadUnit Revived (andreknieriem/open-headunit, AGPL-3.0, Mike Reid lineage). If you give anyone the APK you must offer them this source.

The embedded reference certificate and key (hu15/app/src/main/res/raw/cert, privkey) are HeadUnit Revived's.

Bundled / linked:
- Conscrypt 2.5.3 (Google), Apache-2.0, TLS 1.2 provider (platform TLS on API 15 stops at TLS 1.0).
Not used: HeadUnit Revived's code, gartnera/headunit (AGPL C++), FFmpeg.
