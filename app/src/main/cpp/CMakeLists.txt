# Pulsar USB 独占输出 native 层
#
# 职责最小化：Kotlin 侧已做好描述符解析 / 格式协商 / 时序推导（可单元测试），
# 这里只实现 Android 官方 API 做不到的事——libusb 等时（isochronous）传输泵。
#
# libusb（LGPL-2.1）以静态库编入 pulsar_usb.so。LGPL 静态链接要求对外分发时
# 提供可重链的目标文件；本项目为私有 MVP 不对外分发，暂不涉及。源码 vendor 于
# libusb/（官方 1.0.27），构建配置对齐官方 android/jni/libusb.mk。
#
# 官方 Android 构建清单（android/jni/libusb.mk）编译的正是下面这组文件，
# include 路径含 android/（config.h，libusbi.h 用 #include <config.h> 引入）。

cmake_minimum_required(VERSION 3.22.1)
project(pulsar_usb C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# libusb 静态库（只编译 POSIX + linux_usbfs 后端）
# ---------------------------------------------------------------------------
add_library(libusb STATIC
    libusb/core.c
    libusb/descriptor.c
    libusb/hotplug.c
    libusb/io.c
    libusb/sync.c
    libusb/strerror.c
    libusb/os/linux_usbfs.c
    libusb/os/events_posix.c
    libusb/os/threads_posix.c
    libusb/os/linux_netlink.c
)

target_include_directories(libusb PRIVATE
    libusb/android     # config.h
    libusb             # libusb.h / libusbi.h
)

target_compile_options(libusb PRIVATE
    -pthread
    -fvisibility=hidden
    -Wno-unused-parameter
    -Wno-unused-function
)

# ---------------------------------------------------------------------------
# 等时传输泵 + JNI 桥
# ---------------------------------------------------------------------------
add_library(pulsar_usb SHARED
    usb_audio_driver.cpp
    usb_jni.cpp
)

target_include_directories(pulsar_usb PRIVATE
    libusb             # <libusb.h>
)

target_compile_options(pulsar_usb PRIVATE
    -pthread
)

# libusb 的 ENABLE_LOGGING 走 Android log（android/config.h USE_SYSTEM_LOGGING_FACILITY）
target_link_libraries(pulsar_usb PRIVATE libusb log)
