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
