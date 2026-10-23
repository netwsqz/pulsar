# USB 独占下接管系统音量键

> 状态：实现完成，真机验证通过（前台截获路径）
> 日期：2026-09-14 起，2026-09-15 真机收敛；2026-10-23 修正接管条件（改为「泵正在输出」）并真机复验通过
> 关联：[CLAUDE.md](../CLAUDE.md)、[usb-exclusive-research.md](usb-exclusive-research.md)

## 一句话结论

**MIUI 上 MediaSession 那条标准路是死的**（保留了 `setPlaybackToRemote` 的 API，却删掉了音量键派发，见 §11.2），所以真正干活的是**前台截获**：`MainActivity.dispatchKeyEvent` 吃掉音量键 → 自定义命令 `ADJUST_VOLUME` → `PlaybackService.applyUsbVolume`，算术只在服务一处。

标准路（`RemoteVolumePlayer` + Media3 自动注册的 `VolumeProvider`）**保留但在这台机器上不生效**，它是给 AOSP 设备的，见 §11.4。

## 1. 背景

USB 独占（libusb 等时泵）绕过 AudioFlinger，系统混音器的 `STREAM_MUSIC` 音量对输出**完全无效**。现状是：音量只能通过 App 内滑块调（滑块 → MediaSession 自定义命令 `SET_VOLUME` → `UsbExclusiveController.setVolume(percent)` → 有 Feature Unit 发 UAC2 硬件 SET_CUR，否则 native 软件衰减）。按下手机音量键，调的是那个不起作用的系统音量，用户看到音量条动了、声音没变。

## 2. 平台机制（已查证，AOSP + Media3 1.5.1 源码）

### 2.1 音量键的派发

`PhoneWindowManager.interceptKeyBeforeQueueing` → `MediaSessionLegacyHelper.sendVolumeKeyEvent` → `MediaSessionService.dispatchAdjustVolumeLocked`：

```java
MediaSessionRecordImpl session = mPriorityStack.getDefaultVolumeSession();
if (session == null || preferSuggestedStream) { mAudioManager.adjustSuggestedStreamVolumeForUid(...); }
else { session.adjustVolume(...); }
```

`MediaSessionStack.getDefaultVolumeSession()`（`MediaSessionStack.java:331`）的过滤条件是：

```java
record.checkPlaybackActiveState(true) && record.canHandleVolumeKey()
```

**即：只有处于 PLAYING 状态的会话才会拿到音量键。** 这决定了本功能的核心限制——暂停时音量键归系统，App 拿不到（见 §5 局限）。

`MediaSessionRecord.adjustVolume` 按播放类型分流：`PLAYBACK_TYPE_LOCAL` → 调系统流音量；`PLAYBACK_TYPE_REMOTE` → `VolumeProvider.onAdjustVolume(direction)`（`direction` ∈ `ADJUST_LOWER(-1)` / `ADJUST_RAISE(+1)` / `ADJUST_SAME(0)`，一次按键一次回调，长按按输入重复率连续回调）。

### 2.2 系统音量条

会话注册远程音量后，系统音量面板会多出一行"远程音量"，带 App 的 `remoteLabel`（= App 名），并且**不会**被抑制。`VolumeProviderCompat` 的 `onSetVolumeTo(volume)` 接收音量条的拖动（绝对值），`onAdjustVolume` 接收按键。音量条显示的数字来自 `VolumeProviderCompat.setCurrentVolume()`，不主动调用就是陈旧值（平台有 1 秒乐观猜测兜底）。

### 2.3 Media3 的挂载机制

Media3 **没有**公开的 `setPlaybackToRemote` API。它自己在内部干这件事（`MediaSessionLegacyStub.java:1320`）：

```java
public void onDeviceInfoChanged(int seq, DeviceInfo deviceInfo) {
  PlayerWrapper player = sessionImpl.getPlayerWrapper();
  volumeProviderCompat = player.createVolumeProviderCompat();
  if (volumeProviderCompat == null) { sessionCompat.setPlaybackToLocal(streamType); }
  else { sessionCompat.setPlaybackToRemote(volumeProviderCompat); }
}
```

而 `PlayerWrapper.createVolumeProviderCompat()`（`PlayerWrapper.java:1101`）的开关是 **Player 报上来的 `DeviceInfo.playbackType`**：

- `PLAYBACK_TYPE_LOCAL` → 返回 null → 本地音量
- 否则按可用命令定档：有 ADJUST 命令 → `RELATIVE`；再有 SET 命令 → `ABSOLUTE`（音量条可拖）
- 音量范围取 `DeviceInfo.maxVolume`，初始值取 `getDeviceVolumeWithCommandCheck()`

**挂载/摘除都是公开 API**：`MediaSessionImpl.setPlayerInternal()` 每次换 player 都会调 `dispatchRemoteControllerTaskToLegacyStub(callback -> callback.onPlayerChanged(...))`，而 `onPlayerChanged` 里**无条件**调用 `onDeviceInfoChanged`（`MediaSessionLegacyStub.java:1095`，注释写明 "Forcefully update playback info to update VolumeProviderCompat"）。所以：

- **挂上**：`mediaSession.setPlayer(RemoteVolumePlayer(exoPlayer))`
- **摘掉**：`mediaSession.setPlayer(exoPlayer)`

`MediaSession.Builder` 给 wrapper 的默认玩家命令集是 `DEFAULT_PLAYER_COMMANDS = addAllCommands()`（`MediaSession.java:2016`），`PlayerWrapper.getAvailableCommands()` 取它与 ExoPlayer 命令集的交集——ExoPlayer 本身声明了设备音量命令，因此**当前不需要覆写 `getAvailableCommands()`**。若真机上出现"按键无反应/音量条拖不动"，第一处要查的就是这里（ExoPlayer 的命令集随状态变化，见 §6）。

## 3. 实现

### 3.1 新类 `playback/RemoteVolumePlayer.kt`

```kotlin
interface DeviceVolumeSource {
    fun currentPercent(): Int          // 0..100
    fun onVolumeChange(percent: Int)   // 已是夹紧值
}

class RemoteVolumePlayer(
    delegate: Player,
    private val source: DeviceVolumeSource,
) : ForwardingPlayer(delegate) {
    private var volume = source.currentPercent().coerceIn(0, 100)
    override fun getDeviceInfo() = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMinVolume(0).setMaxVolume(100).build()
    override fun getDeviceVolume() = volume
    override fun isDeviceMuted() = volume == 0
    override fun setDeviceVolume(volume: Int, flags: Int)       // 音量条拖动 + 绝对值
    override fun increaseDeviceVolume(flags: Int)               // +5%
    override fun decreaseDeviceVolume(flags: Int)               // −5%
    override fun setDeviceMuted(muted: Boolean, flags: Int)     // 静音 → 0
}
```

要点：

- 步长 **5%**（`UsbHardwareVolumeControl.percentToDb256` 会再对齐到 DAC 的硬件档位；5% ≈ 3 dB，人耳可辨的一档）。
- 所有写入都 `update()` 夹紧到 0..100 再 `source.onVolumeChange()`，并在 0/100 边界不重复回调。
- `isDeviceMuted` 定义为 `volume == 0`（平台会据此显示静音图标）。
- 音量**唯一真源**仍是 `UsbExclusivePreferences.volumePercent`；`RemoteVolumePlayer` 只是多一个入口，不新增并行状态。构造时读一次初值（挂在 session 建立/重建时，服务内 `usbVolumePercent` 已从 DataStore 载入）。
- 基类用 `ForwardingPlayer`（公开稳定 API，其余几百个方法自动转发）。不用 `SimpleBasePlayer`（`@UnstableApi`，未覆写的命令直接抛异常）。
- 覆写的是带 `flags` 的非废弃重载，**同时把四个无参废弃重载显式接回带参版本**：`ForwardingPlayer` 对它们的默认实现是直接转发给 delegate，会绕过本类的音量转发（实测如此）。

### 3.2 接线 `PlaybackService.kt`

- `onCreate`：先 `usbVolumePercent = usbExclusivePreferences.volumePercent.first()`（`runBlocking`，早于下面的 `launch`，避免 collector 的异步时序问题），再建 MediaSession。
- 新增 `DeviceVolumeSource` 实现：`currentPercent()` 读 `usbVolumePercent`；`onVolumeChange(percent)` 走统一的 `applyUsbVolume(percent)`。
- 新增 `private fun applyUsbVolume(percent: Int)`：`usbVolumePercent = percent` → `usbExclusiveController.setVolume(percent)` → 同步系统面板数字（见下）→ 异步写 DataStore。`SET_VOLUME` 命令处理器与远程音量回调**都**走它，不再各写一遍。
- 新增 `private fun playerForSession(exoPlayer: ExoPlayer): Player`：`if (usbExclusiveEnabled) RemoteVolumePlayer(exoPlayer, remoteVolumeSource) else exoPlayer`。
- `onCreate` 建 session 与 `rebuildPlayer()` 都改为 `mediaSession.setPlayer(playerForSession(newPlayer))`——**挂/摘是同一个调用**，独占开关一切换就自动生效。
- **面板数字同步**：系统音量面板显示的数字取自注册时读过一次的 provider 当前值，App 内滑块调音量不经过音量键路径，面板会一直停在旧值。所以 `applyUsbVolume` 末尾调 `(mediaSession?.player as? RemoteVolumePlayer)?.syncFromSource()`，把面板对齐；`syncFromSource` 只刷新展示、不回调 source，不会形成回环。
- **挂载时机跟随"USB 泵正在输出"（`UsbExclusiveState.Streaming`），不是独占开关**：开关开着但没插设备（或泵还没起来）时声音走系统 `AudioTrack`，此时接管音量键只会去改一个对实际出声无效的 USB 音量，表现就是**音量键完全失灵**（真机反馈，2026-10-23 修正，原先是按开关挂载）。泵起来时由 `usbExclusiveController.state` collector 补挂，停下时摘掉。

## 4. 行为表（真机实测）

| 场景 | 音量键 | 系统音量条 |
|---|---|---|
| 独占开 + 泵在输出（`streaming`）+ **Pulsar 在前台**（不论播放还是暂停） | 调 App 音量（±5%），USB 硬件/软件衰减立即生效，**系统 `STREAM_MUSIC` 纹丝不动** | **不弹出**——按键被完全吃掉（见 §11.3） |
| 独占开但**没插设备 / 泵未就绪** | **还给系统**（声音走系统 AudioTrack，系统音量才有效） | 系统自己的 STREAM_MUSIC 行 |
| 独占开 + 息屏/切到别的 App | **调系统音量，对 USB 无效**（平台限制，见 §5、§11） | 系统自己的 STREAM_MUSIC 行 |
| 独占关 / USB 拔出 | 立刻还给系统（`setPlayer(exoPlayer)` + 清空前台快照），恢复原生行为 | 原生行为 |

## 5. 局限（已知，本次不做）

1. **息屏/后台时音量键不生效**——两条路都过不去：MIUI 根本没有向会话派发音量键的代码（§11.2），而前台截获要求 Activity 可见。要绕开只能对系统长期谎报"正在播放"或申请系统权限，代价过大，已否决。
2. **没有音量视觉反馈**——按键被完全吃掉，MIUI 的面板没机会弹；App 内滑块是唯一能看见数字的地方。Q4 原本想"依赖系统面板"来给反馈，这个 ROM 上兑现不了。
3. **DAC 物理旋钮**——转旋钮 App 完全感知不到（需要轮询 UAC2 GET_CUR），系统音量条与 App 内百分比都会与实际偏差。

## 6. 真机验证清单

**已在本机（MIUI）实测通过**（2026-09-15，探针日志见 §11.3）：`dispatchKeyEvent` 收到按键 → `consumed=true` → `applyUsbVolume(51) 原值=46` → `applyUsbVolume(56) 原值=51` → `applyUsbVolume(51) 原值=56`，同时系统 `streamVolume` 全程停在 3，DataStore 落盘 51。

换设备（尤其是非 MIUI）时按这个顺序重验：

1. 独占开 + Pulsar 在前台按音量键 → 声音变化，且系统 `STREAM_MUSIC` **不动**。
2. **若没反应** → 先确认 `dispatchKeyEvent` 有没有被调到（`MainActivity` 里临时加日志）。调到了说明是命令通道断；没调到说明这个 ROM 连前台按键都不给。
3. **若一次按键跳两档** → 说明系统派发与前台截获各算了一次。本机 MIUI 上系统那条是死的、验不出来。修法是给 `applyUsbVolume` 加几毫秒的按键去重窗口（两条路对同一次按键的到达间隔只有几毫秒，人手连按最快也在 100ms 量级）。**没复现之前不要加。**
4. **系统音量条** → 看 `dumpsys media_session` 里我们会话的 `current` 有没有跟着走（§11.4，目前是死值 0，未查清）。
5. 关掉独占开关 → 音量键立即恢复系统行为。
6. 息屏 + 播放中 → 音量键无效（已知限制，见 §5）。

## 7. 测试策略

- `RemoteVolumePlayer` 是纯逻辑（只依赖 `Player` 接口 + `DeviceVolumeSource`），用**手写 Fake Player**（只实现音量相关方法，其余抛 `NotImplementedError`）做单元测试，不依赖 Android 运行时。
- 覆盖：`DeviceInfo` 是 REMOTE + 0..100；初值读取与夹紧；±5% 步进；边界不回绕、不重复回调；`setDeviceVolume` 夹紧；静音；`isDeviceMuted` 与 0 的对应；`source` 收到的是夹紧后的值；音量源为 null 时静默丢弃不崩溃；非音量调用照常转发给 delegate。
- **测试里的 Fake 同时扮演 Player 与 DeviceVolumeSource 两个角色**（同一个音量值）：生产里 delegate 与 `DeviceVolumeSource` 读写的本来就是同一处（USB 控制器 + `usbVolumePercent`），Fake 不保持单一真源就会出现"步进以谁为基准"的假失败。
- MediaSession 的挂载/摘除是集成行为，单元测试覆盖不到（需 Robolectric/真机），靠 §6 真机清单验证；其正确性已在 §2.3 由 Media3 源码路径确认。

### 7.1 纯 JUnit 覆盖不到的两处（已知）

**其一：命令集。** `getAvailableCommands()` 的正确性**无法在本地单元测试中断言**：Media3 的 `Player.Commands` 内部用 `android.util.SparseBooleanArray`，而本地单元测试（`testOptions.unitTests.isReturnDefaultValues = true`）下该桩不记账，`Commands.size()` 恒为 0、`contains()` 恒为 false。写入逻辑本身不变，只是断言不出来。真机判据见 §6 第 3 条。

**其二：初始化时序。** 服务冷启动时"先同步读偏好、再建播放器与会话"这一顺序无法在单元测试里覆盖——它活在 Android 生命周期里，没有可用的测试 seam（见 §9 的复盘）。

## 8. 首次真机验证发现的问题（已修）

**症状**：USB 独占开着、音乐也在放，但音量键毫无反应；`dumpsys media_session` 显示 `volumeType=LOCAL, max=0`，远程音量 provider 根本没挂上。

**根因**：`usbExclusiveEnabled` 是服务里的一个字段，**只在异步 collector 里被赋值**，而 `onCreate` 里两处同步用到它的地方——`createPlayer(...)`（决定用哪条音频链路）和建 MediaSession（决定要不要包装成远程音量设备）——都跑在 collector 之前。于是冷启动时它停在初始值 `false`：会话拿到的是裸播放器。等 collector 读到 `true` 时，`rebuildPlayer` 又被 `isUsbExclusive == usbExclusiveEnabled` 的守卫挡住（两边都是 `true`），永远不会补挂。

**触发条件**：冷启动或服务重启时独占已经是开的——也就是**绝大多数正常使用场景**。手动关一次再开一次才能挂上，这也是为什么最初的单元测试和代码走查都没发现。

**修复**：`onCreate` 开头用 `runBlocking` 同步读一次 `usbExclusivePreferences.enabled`（音量初值一并读），让后续同步逻辑拿到真实值。**同时必须显式调用 `usbExclusiveController.enable()`**——这一步以前是搭在"首帧 collector 触发 rebuildPlayer"上的，初始值就位后不再触发重建，不补就会导致冷启动时 USB 泵不打开。

**教训**：把"持久化偏好的初始值"和"依赖它的同步初始化"拆在异步 collector 的两侧，是这类 bug 的温床。凡是 `onCreate` 里同步要用的状态，都必须同步读出来。

## 9. 遗留：这里没有好的测试 seam（复盘）

本次的 bug 单元测试抓不到，不是测试写得不够，而是**服务初始化没有可测的接缝**：`onCreate` 把"读偏好 → 建播放器 → 建会话 → 开启/停用独占控制器"揉在一个生命周期回调里，既不能单测，也不容易看出谁依赖谁。

若要根治，应把这段初始化抽成一个纯函数/小对象（输入：偏好快照；输出：一组初始化决定——用哪条音频链路、是否包装播放器、控制器的启停），在 `onCreate` 里只做"读一次 → 交给它执行"。那样本次这条"初始值决定包装"的规则就能被一条单元测试钉住。这属于架构改动，不在本次范围内，记在这里备查（可交给 `/improve-codebase-architecture` 评估）。

## 10. 实现中修正的四处初始判断

1. **步进的基准是音量源，不是被包装播放器**：初版写成 `delegate.getDeviceVolume() + STEP`，测试暴露了"delegate 与源各算一次"的双重累加。独占路径下 delegate 并不掌握真实音量，`DeviceVolumeSource` 才是唯一真源（也避免了给 delegate 写回音量的耦合）。
2. **音量源做成可空**：本地模式（USB 独占关闭）下包装仍可能挂在 session 上，此时音量键操作必须静默丢弃而不是崩溃或产生副作用。
3. **四个无参废弃重载必须显式接回带参版本**：`ForwardingPlayer` 对 `setDeviceVolume(int)` / `increaseDeviceVolume()` / `decreaseDeviceVolume()` / `setDeviceMuted(boolean)` 的默认实现是**直接转发给 delegate**，会绕过本类覆写的音量逻辑。
4. **补 `syncFromSource()`**：系统音量面板的数字取自注册时读过一次的 provider 当前值，App 内滑块调音量不走音量键路径，面板会一直停在旧值。由服务在统一音量入口处调一次同步（只刷展示、不回调，避免回环）。

## 11. MIUI 上 MediaSession 路径整条失效（真机结论）+ 前台截获兜底

### 11.1 症状

会话已正确报 `volumeType=REMOTE, controlType=ABSOLUTE, max=100`、状态 PLAYING，但按音量键：**系统音乐流的音量条动了，App 一个回调都没收到，USB 音量纹丝不动。**

### 11.2 排查与证据

- `input keyevent 24` 注入：`STREAM_MUSIC` 6 → 7，App 侧探针 **0 条日志**。
- 弹出的是 MIUI 自己的音量面板（`MiuiVolumeDialogMotion` / `VolumePanelDialogController`），走本地流。
- 把该机 `framework.jar`（44 MB）反查符号表：

| 符号 | 在 AOSP 里的作用 | 该机 |
|---|---|---|
| `setPlaybackToRemote` / `VolumeProvider` / `onAdjustVolume` | 声明并接收远程音量 | ✅ 有 |
| `getRemoteStreamVolume` | 音量键派发时判断"有没有远程音量会话" | ❌ 无 |
| `STREAM_REMOTE_MUSIC` / `isRemoteStream` / `mHasRemotePlayback` | 音量面板判断是否为远程流 | ❌ 无 |

**结论：MIUI 保留了远程音量的 API（所以调用不报错、会话也确实报 REMOTE），却删掉了"把音量键转发给远程会话"那段派发。这是 ROM 行为，不是 App 的 bug，App 侧绕不过去。**

### 11.3 兜底：前台截获（`ForegroundVolumeKeys` + `MainActivity.dispatchKeyEvent`）

`MainActivity.dispatchKeyEvent` 吃掉 `KEYCODE_VOLUME_UP/DOWN`，转成会话命令下发。不依赖任何系统派发逻辑。**真机探针已证实这条路通**：`dispatchKeyEvent code=24 action=0 consumed=true`。

- **通道用自定义命令 `ADJUST_VOLUME`（传 ±1 档），不用 `MediaController.increaseDeviceVolume()`。** 后者要 MediaSession 把设备音量命令传给控制器，实测靠不住（`isCommandAvailable` 时真时假）；自定义命令这条是 App 内滑块一直在用、验证过好用的。
- **算术只在服务一处**：`ADJUST_VOLUME` 只传"加一档/减一档"，`PlaybackService` 按自己手里的 `usbVolumePercent` 算。UI 侧只有 500ms 轮询来的快照，自己算会把连按的第二下算成同一个目标值而丢档。
- **DOWN 与 UP 必须同进同退**（`lastDownConsumed`）。第一版只在 DOWN 上判断、UP 一律吃掉，结果 DOWN 落到系统手里、UP 被 App 吃掉，系统行为变得莫名其妙（音量反而完全不响应了）。**这是本次唯一一个真正的实现 bug，且只有真机能暴露。**
- **门**：独占关闭、控制器未连、命令不可用——三种情况 DOWN/UP 一律放行。宁可恢复系统行为，也不要吃掉按键却什么都不做。
- **代价**：只在 Pulsar 位于前台时有效。息屏/后台仍调不了（见 §5）。
- **副作用：系统音量面板不再弹出。** 按键被完全吃掉，MIUI 的面板也就没机会显示；App 内音量滑块是唯一的视觉反馈。这是 Q4"依赖系统面板"在当前 ROM 上无法兑现的部分——不是没实现，是这个 ROM 根本没有可用的面板。

### 11.4 未查清的遗留：平台侧远程音量看板是死的

`dumpsys media_session` 里我们的会话报 `volumeType=REMOTE, controlType=ABSOLUTE, max=100`，但 **`current=0` 且 `volumeControlId=null`**——`max=100` 说明平台确实读到了 `RemoteVolumePlayer.getDeviceInfo()`，`current=0` 却对不上（`getDeviceVolume()` 应为 46→51）。

可能原因：`ExoPlayer`/`RemoteVolumePlayer` 从不发 `onDeviceVolumeChanged` 回调，平台缓存的当前值只取注册那一刻读到的数，之后不再更新。**没查清，也不影响音量键功能**（那条路已经不走平台了），但它意味着"系统音量条显示/拖动 Pulsar 音量"这个能力在**任何** ROM 上都存疑，不只是 MIUI。

换非 MIUI 设备验证时，这是第一个要看的地方。

### 11.5 复现不了的东西不要硬猜

`framework.jar` 那一步是转折点：在此之前所有证据都指向"我们的代码某处没接上"，只有把 ROM 的符号表摊开才看清是平台缺了整段派发。**在"我们的代码"里找不到原因时，去查平台有没有这段代码，比继续加日志有效。**

第二次转折是**上探针而不是继续读代码**：`commands 可用=false` 和 `action=1 consumed=true` 这两条，靠读代码是推不出来的——前者是运行时状态，后者是我自己写的逻辑漏洞。一次构建换回全部真相。
