// JNI 桥：Kotlin UsbNativeDriver ↔ native UsbAudioDriver。
//
// 两阶段生命周期：
// - nativeOpen → 设备级 open()（wrap fd + claim + set alt），成功后返回句柄，
//   失败返回负错误码（UsbAudioDriver::Error 的值）。
// - nativeStartStream / nativeStopStream → 流级 start_stream()/stop_stream()，
//   可反复调用，始终保留接口 claim（切歌/换采样率不重抢接口，见研究文档坑 #1）。
// - nativeClose → close()：停泵、释放接口、销毁句柄。
//
// fd 所有权归 Kotlin（UsbDeviceConnection）：vendored libusb 1.0.27 的
// fd_keep=1 使 libusb_close 不关 wrapped fd，native 从不 close fd。
// 调用方在 nativeClose 之后、以及 nativeOpen 返回负错误码时，自行 close 连接。
// nativeClose 阻塞等待事件线程退出；返回后句柄失效，禁止再次调用。

#include <jni.h>

#include <cstdint>

#include "usb_audio_driver.h"

namespace {

inline pulsar::UsbAudioDriver* from_handle(jlong h) {
  // 0 无效；-1..-7 是 nativeOpen 失败时返回的 UsbAudioDriver::Error 枚举值，
  // 绝不能被当作指针解引用。真正的句柄地址理论上可能落入这个区间，但 Android
  // 进程虚拟地址空间的低 4KB 是内核保留的不可映射页，实际不会分配到这里，
  // 拒绝这七个值不会误杀合法句柄。
  if (h >= -7 && h <= 0) return nullptr;
  return reinterpret_cast<pulsar::UsbAudioDriver*>(h);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeOpen(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint interfaceNumber, jint altSetting,
    jint endpointAddress, jint uacVersion, jint clockId, jint controlInterfaceNumber,
    jint featureUnitId, jint volumeChannelMask) {
  auto* driver = new pulsar::UsbAudioDriver(
      fd, interfaceNumber, altSetting, endpointAddress, uacVersion, clockId,
      controlInterfaceNumber, featureUnitId, volumeChannelMask);
  pulsar::UsbAudioDriver::Error err = driver->open();
  if (err != pulsar::UsbAudioDriver::Error::kOk) {
    delete driver;
    return static_cast<jlong>(err);
  }
  return reinterpret_cast<jlong>(driver);
}

JNIEXPORT jint JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeStartStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint sampleRate, jint ticksPerPacket,
    jint bytesPerFrame, jlong framesPerUframeQ16, jint maxPacketSize, jint altSetting) {
  auto* driver = from_handle(handle);
  // 句柄无效（nativeClose 后继续调用等违规路径）：返回统一失败码。
  if (driver == nullptr) return static_cast<jint>(pulsar::UsbAudioDriver::Error::kInit);
  pulsar::UsbAudioDriver::Error err = driver->start_stream(
      sampleRate, ticksPerPacket, bytesPerFrame, static_cast<uint64_t>(framesPerUframeQ16),
      maxPacketSize, altSetting);
  return static_cast<jint>(err);
}

JNIEXPORT void JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeStopStream(JNIEnv* /*env*/,
                                                                      jobject /*thiz*/,
                                                                      jlong handle) {
  auto* driver = from_handle(handle);
  if (driver != nullptr) driver->stop_stream();
}

JNIEXPORT jint JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray data, jint offset, jint length) {
  auto* driver = from_handle(handle);
  if (driver == nullptr || length <= 0) return 0;
  const jsize array_len = env->GetArrayLength(data);
  // 先验 offset ∈ [0, array_len]，再比 length，避免 offset+length 溢出。
  if (offset < 0 || offset > array_len) return 0;
  if (length > array_len - offset) return 0;
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (bytes == nullptr) return 0;
  const int accepted =
      driver->write(reinterpret_cast<const uint8_t*>(bytes) + offset, length);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  return accepted;
}

JNIEXPORT void JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeFlush(JNIEnv* /*env*/,
                                                                 jobject /*thiz*/,
                                                                 jlong handle) {
  auto* driver = from_handle(handle);
  if (driver != nullptr) driver->flush();
}

JNIEXPORT void JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeClose(JNIEnv* /*env*/,
                                                                 jobject /*thiz*/,
                                                                 jlong handle) {
  auto* driver = from_handle(handle);
  if (driver != nullptr) {
    driver->close();
    // 事件线程 join 失败（设备彻底无响应）时绝不能 delete：线程仍可能在运行、
    // 仍可能访问该对象的成员，析构一个 joinable 的 std::thread 会
    // std::terminate() 崩溃整个进程。接受一次性内存泄漏，换取不发生 UB。
    if (!driver->is_thread_leaked()) delete driver;
  }
}

JNIEXPORT jlong JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeGetPlayedFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr) ? static_cast<jlong>(driver->played_frames()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeGetHeartbeat(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr) ? static_cast<jlong>(driver->heartbeat()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeIsLoopExited(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr && driver->loop_exited()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeGetUnderruns(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr) ? static_cast<jlong>(driver->underruns()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeIsHighSpeed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr && driver->is_high_speed()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeHasHardwareVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  return (driver != nullptr && driver->has_hardware_volume()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeGetHardwareVolumeRange(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  auto* driver = from_handle(handle);
  if (driver == nullptr || !driver->has_hardware_volume()) return nullptr;
  jint values[3] = {driver->hardware_volume_min_db256(),
                    driver->hardware_volume_max_db256(),
                    driver->hardware_volume_step_db256()};
  jintArray result = env->NewIntArray(3);
  if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
  return result;
}

JNIEXPORT void JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeSetVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint percent) {
  auto* driver = from_handle(handle);
  if (driver != nullptr) driver->set_volume(static_cast<int>(percent));
}

JNIEXPORT void JNICALL
Java_com_example_pulsar_playback_usb_UsbNativeDriver_nativeSetMuted(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jboolean muted) {
  auto* driver = from_handle(handle);
  if (driver != nullptr) driver->set_muted(muted == JNI_TRUE);
}

}  // extern "C"
