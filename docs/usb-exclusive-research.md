# USB 独占输出（真·bit-perfect）技术调研与实现路线

> 状态：调研完成，进入实现阶段
> 日期：2026-08-04
> 关联：[CLAUDE.md](../CLAUDE.md)、[MVP_PLAN.md](../MVP_PLAN.md)

## 一句话结论

实现「USB 独占」有两条路：**系统 API（Android 14+ bit-perfect mixer）** 和 **libusb 直写 USB 设备**。经调研确认：系统 API 路线需要手机厂商配合、且已验证本设备不支持，走不通；**libusb 路线**有完整可参考的开源实现（Tryptify），技术栈与 Pulsar 几乎一致（Kotlin + Compose + Media3），是本项目应走的路线。核心难点是等时传输（Android 官方 API 不支持，必须靠 libusb + 原生层），其余全部可以在 Kotlin 层完成并做单元测试。

---

## 1. 背景：为什么做 USB 独占

当前 Pulsar 已有**系统级 USB 路由**（`PlaybackService` 里 `setPreferredAudioDevice` + `AudioDeviceCallback`）：声音保证从 USB DAC（小尾巴）出来，但数据流仍经过 Android 混音器 AudioFlinger，可能被重采样/混音/应用音量处理。

「USB 独占」= 绕过 AudioFlinger，把 PCM 直接灌给 USB DAC。好处：
- 位完美输出（bit-perfect）：不重采样、不混音、不截位深
- 采样率上限提升到 USB 硬件本身的能力（如 24bit/192kHz）
- 音量由 DAC 硬件控制，不受系统混音器影响

代价：需要原生层、每款设备都有个性化怪癖、真机验证成本高。

## 2. 两条技术路线对比

| | 路线 A：系统 API（Android 14+） | 路线 B：libusb 直写（本项目选用） |
|---|---|---|
| 原理 | `setPreferredMixerAttributes` + `MIXER_BEHAVIOR_BIT_PERFECT`，声明独占 + 不重采样 | 用户空间直接访问 USB 设备（`libusb_wrap_sys_device`），绕过整个音频 HAL |
| 前置条件 | **需要手机 OEM 厂商支持**（Pulsar 已探测本设备不支持） | 需要 USB Host 支持 + 用户授权；多数设备还需开发者选项「Disable USB audio routing」 |
| 独占程度 | 系统混音器承认的独占，仍可能受系统限制 | 真·独占，物理层直连 |
| 原生依赖 | 无（纯 Kotlin） | 必须 NDK + libusb |
| 可行性 | ❌ 已验证本设备不可行 | ✅ 有完整开源参考（Tryptify） |

**已做过的验证**：`probeBitPerfectSupport`（`PlaybackService.kt`）探测本设备 `getSupportedMixerAttributes` 无 `MIXER_BEHAVIOR_BIT_PERFECT`；真机验证结论见 memory：`bit-perfect-verification` —— 本设备不支持 bit-perfect mixer，float 输出为当前最优。因此路线 A 在本项目不可行，路线 B 是唯一能实现真·独占的途径。

## 3. 参考项目分析

### 3.1 SaltPlayerSource（作者给出的参考）—— 半成品，仅作技术方向参考
- 仓库 **没有 App 源码**（仅发布 + 反馈）。USB 独占代码在作者另一实验仓库 [Moriafly/AndroidUsbAudio](https://github.com/Moriafly/AndroidUsbAudio)，README 自述「**未完成的**」。
- 技术路线正确：`UsbManager` 拿 fd → `libusb_wrap_sys_device` → `claim_interface` → 等时传输泵（`usbaudio_dump.c`）。
- 局限：只针对特定设备写死参数、等时传输未做完整（多 transfer 排队已示范）、无 44.1kHz 家族支持、无反馈端点处理。
- 大量设备怪癖在其 issue 中可见：`#2071`（22050Hz 兼容）、`#1040`（44.1kHz 电流声/底噪）、`#2098`（DAC 硬件音量级数）。

### 3.2 Tryptify（tryptz/monotrypt.android）—— 完整可参考的实现 ★核心参考
一个开源的 Android 发烧级播放器，**完整实现 libusb UAC1+UAC2 独占输出**，技术栈与 Pulsar 几乎一致（Kotlin + Compose + Media3/ExoPlayer）。

关键源码位置（`app/src/main/`）：
- `cpp/usb/libusb_uac_driver.{h,cpp}` — 原生驱动核心（1381 行）
- `cpp/usb/usb_jni.cpp` — JNI 桥
- `java/.../audio/usb/LibusbAudioSink.kt` — Media3 `ForwardingAudioSink` 包装（528 行），**ExoPlayer 集成关键**
- `java/.../audio/usb/LibusbUacDriver.kt` — Kotlin 侧权限/fd/启停
- `java/.../audio/usb/UsbExclusiveController.kt` — 开关 + 设备插拔状态机
- `java/.../audio/UsbAudioRouter.kt` — 系统级路由（等价 Pulsar 现有 `setPreferredAudioDevice`）

**架构流程**：Kotlin 申请 USB 权限 → `openDevice` 拿 fd → native `libusb_wrap_sys_device` → 枚举描述符选匹配的 alt setting → `claim_interface`（含 AudioControl 接口）→ `SET_CUR` 设采样率（UAC2 走 clock entity，UAC1 走 endpoint）→ 预分配等时 transfer 池 + 事件线程跑 `libusb_handle_events` → `LibusbAudioSink` 把 PCM 灌入 SPSC ring。

### 3.3 次要参考
- **Auxio**（OxygenCobalt/Auxio）：issue #1210 讨论系统 API 路线，状态 blocked（等 OEM 支持）——印证路线 A 的现状。
- **liquid-music-android**（admin0330）：Kotlin + Media3 + USB DAC bit-perfect，很新很小，可扫一眼。
- **google/sound-checker**：官方检测设备 bit-perfect 支持的工具（Salt 作者推荐）。

## 4. 推荐架构（Pulsar 落地）

**核心原则：native 面最小化，格式/时序决策全在 Kotlin（可 TDD），C++ 只做 Android API 做不到的事（等时传输）。**

`UsbDeviceConnection` 官方 API 已支持：claim interface、set alt setting、`controlTransfer`（可发 SET_CUR 设采样率、GET_DESCRIPTOR 拉描述符）。**唯一必须 libusb 的是等时（isochronous）传输和反馈端点读取**。

```
┌────────────────────────────── Kotlin（可单元测试） ──────────────────────────────┐
│ UsbExclusiveController      设备发现/权限/插拔状态机                                │
│ UsbDescriptorParser         解析 CONFIGURATION 描述符字节流 → 支持的格式表（TDD）  │
│ UsbFormatNegotiator         根据播放格式挑选 alt setting / 计算每包帧数（TDD）      │
│ LibusbAudioSink             Media3 AudioSink 包装：喂 PCM、看门狗、切歌/暂停处理    │
└─────────────────────────────────────────┬──────────────────────────────────────┘
                                          │ JNI：fd / 参数 / PCM 字节
┌─────────────────────────────────────────▼──────────────────────────────────────┐
│ native（C++ + libusb，LGPL-2.1 动态链接）                                          │
│ wrap_sys_device → claim → set alt → set rate（由 Kotlin 传入参数）                 │
│ 等时传输泵：预分配 transfer 池 + 事件线程                                          │
│ 反馈端点（async pacing，后期）                                                     │
└──────────────────────────────────────────────────────────────────────────────────┘
```

设计取舍说明：
1. **描述符解析放 Kotlin**：raw descriptor 是字节流，纯函数解析可做 TDD（用真实 DAC 的 hex dump 做测试 fixture），符合项目强约定。
2. **采样率协商放 Kotlin**：用 `controlTransfer` 发 SET_CUR（UAC2 时钟实体 / UAC1 端点两套），失败映射成友好错误。
3. **44.1kHz 家族**：等时每包帧数不是整数（44.1k/125µs = 5.5125），需 16.16 定点分数累加器（Bresenham 风格，纯数学，放 Kotlin 可单测）。**MVP 建议先支持 44.1 与 48 两大家族**，因为 Jellyfin 音乐源两者都常见。
4. **进度上报**：ExoPlayer 的 `getCurrentPositionUs`/`hasPendingData`/`isEnded` 必须用「设备实际消费的帧数」（`playedFrames`），不能用「灌入 ring 的帧数」，否则 30 秒的歌 3 秒就被判结束。

## 5. 分阶段实现计划

| 阶段 | 内容 | 验证方式 | 依赖 |
|---|---|---|---|
| **0 工具链** | 安装 NDK + CMake；`build.gradle.kts` 加 `externalNativeBuild`；引入 libusb；Hello World JNI 冒烟 | `assembleDebug` 出 APK | 用户装 NDK（或 sdkmanager 自动装） |
| **1 Kotlin 层** | USB 权限流程、设备发现、描述符解析（TDD）、格式协商（TDD）、状态机（TDD） | 单元测试全绿 | 无硬件 |
| **2 native 驱动** | iso 传输泵、claim/set-alt/set-rate、事件线程 | 真机 `dumpsys` / 日志确认 pump 运行 | 阶段 0 |
| **3 Media3 集成** | `LibusbAudioSink`（ForwardingAudioSink）、看门狗兜底、切歌不释放接口、暂停清缓冲 | 真机听感 + logcat | 阶段 1+2 |
| **4 UI** | 设置开关、独占状态展示（采样率/位深/时钟）、错误提示（引导开发者选项） | 真机 | 阶段 3 |
| **5 真机验证** | 小尾巴 + 播放 44.1/48/96/192，验证 bit-perfect、切歌/暂停/拔插稳定性、不同采样率切换 | 用户亲听 | 阶段 4 |

> 分阶段理由：阶段 1 不依赖硬件、能产出可单测的纯逻辑，是 TDD 主战场；阶段 2 起必须真机。每阶段独立可验证，可随时暂停。

## 6. 已知坑与对策（Tryptify 沉淀 + Salt 用户反馈）

1. **切歌不能释放 USB 接口**（`flushRing`）：每首歌之间只清空缓冲，**绝不** `release_interface`。一释放，内核音频驱动短暂抢回接口，下一首 `claim_interface` 返回 `LIBUSB_ERROR_BUSY`，播放哑死，只能拔线重插。
2. **进度用「已播放帧」不用「已写入帧」**：音频线程灌数据比真实播放快十几倍，用写入帧数会让 ExoPlayer 误判曲目结束 → 5 秒播放后跳下一首。
3. **暂停 = 清空缓冲，不停泵**：停泵要等已提交 URB 排空（约 1 秒），会卡住音频线程。
4. **看门狗兜底**：部分 xHCI 控制器（如 OnePlus）上 iso 回调不触发，表现为「响几秒后死」。对策：400ms 内 `playedFrames` 无进展 → 判定卡死 → 回退系统默认 AudioSink。
5. **44.1kHz 底噪/电流声**（Salt #1040）：切歌时采样率未正确切换导致。对策：切格式时保持接口 claim、严格 SET_CUR 回读校验。
6. **开发者选项「Disable USB audio routing」**：多数设备必须开，否则内核 snd-usb-audio 占着接口，`claim_interface` 返回 BUSY。UI 要引导用户。
7. **采样率/位深不兼容**（Salt #2071）：DAC 的 GET_RANGE 报告支持范围，协商时如实呈现给用户，不做静默降级。
8. **权限广播**：Android 14+ 注册 `RECEIVER_NOT_EXPORTED`；`PendingIntent` 用 `FLAG_MUTABLE`。

## 7. 许可证说明（重要）

- **Tryptify 是 GPL-3.0**，Salt 实验项目也是 GPL-3.0。**参考设计思路、自己重写不受影响；逐行抄代码则 Pulsar 需改用 GPL 兼容协议发布**。本实现按「参考架构 + 自写」处理，注释里保留设计出处。
- **libusb 是 LGPL-2.1**：以动态链接（`.so`）方式使用不传染，Pulsar 可保持闭源协议。
- `{AL2.0,LGPL2.1}` 已在 `packaging.resources.excludes` 排除。

## 8. 真机验证方案

- 设备：kngqay9deamvswww（或当前 adb 在线设备），USB 小尾巴/DAC。
- 验证清单：
  - [ ] 开关开启后，独占泵启动，声音从 DAC 出（断开系统路由确认无重复声源）
  - [ ] 44.1 / 48 / 96 / 192 kHz 各自能播，无爆音/底噪/变速
  - [ ] 切歌、暂停/继续、拖进度条稳定（不释放接口、进度正确）
  - [ ] 拔插 USB 后自动重连/回退系统，不崩溃
  - [ ] 独占失效（开发者选项未开）时给出可操作错误提示
- 采样率真实性：看 DAC 指示灯（若支持）或 `dumpsys audio` / logcat 中 pump 上报的协商参数。

## 9. 关键参考链接

- Tryptify（主参考）：https://github.com/tryptz/monotrypt.android
- Salt USB 实验项目（半成品，路线参考）：https://github.com/Moriafly/AndroidUsbAudio
- SaltPlayerSource（issue 里的设备反馈）：https://github.com/Moriafly/SaltPlayerSource
- libusb：https://github.com/libusb/libusb （Android 构建见其 wiki）
- libusb-java（Salt 移植来源）：https://gitlab.ost.ch/tech/inf/public/libusb-java/
- usbaudio-android-demo（Salt 测试来源）：https://github.com/shenki/usbaudio-android-demo
- Android 官方 USB 数字音频文档：https://source.android.com/docs/core/audio/usb
- Google sound-checker：https://github.com/google/sound-checker
- Auxio issue（系统 API 路线现状）：https://github.com/OxygenCobalt/Auxio/issues/1210
