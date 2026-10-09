// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI glue: OpenH264 (BSD-2, Cisco) software H.264 decode -> tightly packed I420 in a direct ByteBuffer.
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <sys/ioctl.h>
#include "codec_api.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "hu15n", __VA_ARGS__)

#if defined(__i386__) || defined(__x86_64__)
#include <unistd.h>
// openh264 renames NDK cpu-features symbols (android_getCpuCount -> wels_getCpuCount) but only compiles that file for ARM.
extern "C" int wels_getCpuCount(void) { return (int)sysconf(_SC_NPROCESSORS_CONF); }
#endif

extern "C" {

JNIEXPORT jlong JNICALL Java_hu_hu15_H264Decoder_nInit(JNIEnv*, jclass, jint threads) {
  ISVCDecoder* d = nullptr;
  if (WelsCreateDecoder(&d) != 0 || !d) return 0;
  SDecodingParam p;
  memset(&p, 0, sizeof(p));
  p.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_AVC;
  p.eEcActiveIdc = ERROR_CON_SLICE_COPY;  // conceal lost slices by copying, cheapest
  int t = threads;
  d->SetOption(DECODER_OPTION_NUM_OF_THREADS, &t);
  if (d->Initialize(&p) != 0) { WelsDestroyDecoder(d); return 0; }
  int level = WELS_LOG_QUIET;
  d->SetOption(DECODER_OPTION_TRACE_LEVEL, &level);
  LOG("openh264 decoder up, threads=%d", threads);
  return (jlong)(intptr_t)d;
}

// Decodes one Annex-B access unit. Returns 1 if a picture was produced (written to out as I420, info = {w,h}), 0 if not, <0 on error.
JNIEXPORT jint JNICALL Java_hu_hu15_H264Decoder_nDecode(JNIEnv* env, jclass, jlong h, jbyteArray nal, jint off, jint len,
                                                         jobject out, jint outCap, jintArray info) {
  ISVCDecoder* d = (ISVCDecoder*)(intptr_t)h;
  jbyte* src = env->GetByteArrayElements(nal, nullptr);
  unsigned char* planes[3] = {nullptr, nullptr, nullptr};
  SBufferInfo bi;
  memset(&bi, 0, sizeof(bi));
  DECODING_STATE st = d->DecodeFrameNoDelay((const unsigned char*)src + off, len, planes, &bi);
  env->ReleaseByteArrayElements(nal, src, JNI_ABORT);
  if (st != dsErrorFree && !(bi.iBufferStatus == 1)) { if (st & (dsNoParamSets | dsDataErrorConcealed)) return 0; }
  if (bi.iBufferStatus != 1) return 0;
  int w = bi.UsrData.sSystemBuffer.iWidth, hgt = bi.UsrData.sSystemBuffer.iHeight;
  int ys = bi.UsrData.sSystemBuffer.iStride[0], cs = bi.UsrData.sSystemBuffer.iStride[1];
  int need = w * hgt * 3 / 2;
  if (need > outCap) return -2;
  unsigned char* o = (unsigned char*)env->GetDirectBufferAddress(out);
  for (int y = 0; y < hgt; y++) memcpy(o + y * w, planes[0] + y * ys, w);
  unsigned char* u = o + w * hgt;
  unsigned char* v = u + (w / 2) * (hgt / 2);
  for (int y = 0; y < hgt / 2; y++) {
    memcpy(u + y * (w / 2), planes[1] + y * cs, w / 2);
    memcpy(v + y * (w / 2), planes[2] + y * cs, w / 2);
  }
  jint wh[2] = {w, hgt};
  env->SetIntArrayRegion(info, 0, 2, wh);
  return 1;
}

// USBDEVFS_RESET (_IO('U', 20)): re-enumerate the device, like unplugging it. Used to drop a stale AOA session.
JNIEXPORT jint JNICALL Java_hu_hu15_UsbTransport_nReset(JNIEnv*, jclass, jint fd) { return ioctl(fd, _IO('U', 20), 0); }

JNIEXPORT void JNICALL Java_hu_hu15_H264Decoder_nFree(JNIEnv*, jclass, jlong h) {
  ISVCDecoder* d = (ISVCDecoder*)(intptr_t)h;
  if (d) { d->Uninitialize(); WelsDestroyDecoder(d); }
}

}  // extern "C"
