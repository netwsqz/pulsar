# PlaybackSession 重构执行计划

> 状态：**已落地（2026-11-09），待真机确认**。对应 #1 / #2 / #3，三张票代码完成、单测全绿、
> 已本地提交。本节保留决策历史；「队列单一真相」拆到了 #28。本文是 candidate 1 的执行规格，不是 ADR。
> 默认决策取自架构评审的推荐答案；每一步开工前可以改，改了就更新本文。
> `PlaybackSession` / `PlaybackRemote` 已写进 `CONTEXT.md`。

---

## 1. 目标

把散落在 `MainActivity.kt`、`NowPlayingScreen.kt`、`PlaybackService.kt` 里的播放队列语义，收进一个深 module：

- **interface 小**：`state: StateFlow<PlaybackUiState>` + `dispatch(intent)` + `events: SharedFlow<PlaybackEvent>`。
- **implementation 大**：MediaController 生命周期、500ms 轮询、自定义命令、MediaItem 构造、队列增删改排、错误监听全部藏在里面。
- **seam 真实**：`PlaybackRemote` 有生产 adapter（MediaController）和测试 adapter（Fake），测试和生产走同一个 interface。
- **locality**：播放 / 队列的 bug 只改一个地方。
- **leverage**：迷你条、全屏播放页、设置页、详情页共用一套 intent。

## 2. 非目标（这轮明确不碰）

- 不改 `PlaybackService` 的服务形态（仍是 Media3 `MediaSessionService`）。
- 不改 USB 原生层、`UsbExclusiveController` / `UsbExclusiveStateMachine` / libusb 泵。
- 不改视觉、布局、动画、设计 token。
- 不改离线、下载、会话、QQ 下载语义。
- 不引入导航库、DI 框架或新依赖。
- 不顺手修 `playTracks` 遇到第一首 null URL 就整条放弃的行为（另开 ticket）。

## 3. 默认决策（可改）

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 边界 | **C**：接整个播放客户端（连接、监听、轮询、状态、队列、播放模式、USB/静音/音量），对外两个状态面：`PlaybackUiState` 与 `UsbOutputState` |
| D2 | 顺手修什么 | **B**：提取 + 把 `playbackMode` 收进 module；队列单一真相、null URL 容错另开 ticket |
| D3 | 落地切法 | **B**：四个可独立构建/真机验证的 slice |
| D4 | 测试边界 | **A**：新增 `PlaybackSessionTest` + `FakePlaybackRemote`；不写真实 MediaController 集成测试；USB 相关旧测试保留 |
| D5 | 真机验收 | 按 §8 清单逐项点，转屏与杀进程重进必须包含 |
| D6 | 模块生命周期 | **slice 2 先用 `remember` + `DisposableEffect`（与今天一致）；slice 4 再评估是否迁到 Activity 级或 ViewModel** |

> D6 的影响：`playbackMode` 虽然进了 module，但 module 仍随 composition 重建，所以转屏会重置。要真正跨转屏保留，需要 slice 4 的 ViewModel/Activity 级持有；这一点在 slice 3 的验收里单独标注。

## 4. 目标形状（sketch，slice 2 再定稿）

> **2026-11-09 注**：本节是开工前的草图，**落地后与草图有出入，以代码为准**。已知差异：
> - `dispatch(intent)` 实际返回 `Boolean`（界面用它判断这次点击有没有被接住），不是 `Unit`；
> - `PlaybackIntent` 多了 `PlayLocalFile(uri)`、`PlayQueueItem(index)`、`AdjustVolume`、`SetUsbExclusive`、`SetUsbVolume`、`SetSilenceSkip`；
> - `PlaybackUiState` 多了 `currentMediaItem`（迷你条/全屏页要拿它画封面）；
> - `PlaybackEvent` 有四个变体：`Message` / `Unplayable` / `PlaybackQueuePrepared` / `VolumeApplied`；
> - `PlaybackModeStore` 是后加的第五个文件，负责播放模式落盘（DataStore 键 `playback_mode`）。
>
> 读代码：`app/src/main/java/com/example/pulsar/playback/PlaybackSession.kt`。

```kotlin
// playback/PlaybackRemote.kt
interface PlaybackRemote {
    fun play()
    fun pause()
    fun prepare()
    fun setQueue(items: List<MediaItem>, startIndex: Int)
    fun setSingleItem(item: MediaItem)
    fun insertAfterCurrent(item: MediaItem): Boolean
    fun removeAt(index: Int)
    fun move(from: Int, to: Int)
    fun seekTo(positionMs: Long)
    fun seekToDefaultPosition(index: Int)
    fun seekToNext()
    fun seekToPrevious()
    fun snapshot(): RemoteSnapshot
    fun addListener(listener: RemoteListener)
    fun removeListener(listener: RemoteListener)
    fun send(command: SessionCommand, args: Bundle): ListenableFuture<SessionResult>
}
```

```kotlin
// playback/PlaybackSession.kt
class PlaybackSession(
    private val remote: PlaybackRemote,
    private val mediaItems: TrackMediaItemFactory,
    private val gate: PlaybackGate,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<PlaybackUiState>
    val events: SharedFlow<PlaybackEvent>

    /** 唯一入口；扩展函数负责可读性。 */
    fun dispatch(intent: PlaybackIntent)
    fun close()
}

sealed interface PlaybackIntent {
    data class Play(val tracks: List<Track>, val startTrackId: String) : PlaybackIntent
    data class AddToQueue(val track: Track) : PlaybackIntent
    data class RemoveFromQueue(val ids: Set<String>) : PlaybackIntent
    data class Reorder(val ordered: List<QueueTrack>) : PlaybackIntent
    data class SeekTo(val positionMs: Long) : PlaybackIntent
    data object Next : PlaybackIntent
    data object Previous : PlaybackIntent
    data object TogglePlayPause : PlaybackIntent
    data class SetMode(val mode: PlaybackMode) : PlaybackIntent
    data class SetUsbExclusive(val enabled: Boolean) : PlaybackIntent
    data class SetUsbVolume(val percent: Int) : PlaybackIntent
    data class SetSilenceSkip(val enabled: Boolean) : PlaybackIntent
    data class AdjustVolume(val step: Int) : PlaybackIntent
}
```

```kotlin
// 对外状态：把 3 个 lambda 从 ControllerState 里拿掉
data class PlaybackUiState(
    val isConnected: Boolean,
    val status: String,
    val playbackError: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val queue: List<QueueTrack>,
    val mode: PlaybackMode,
    val usb: UsbOutputState,
    val silenceSkipEnabled: Boolean,
)

data class UsbOutputState(
    val enabled: Boolean,
    val state: String,
    val error: String?,
    val pending: Boolean,
    val volumePercent: Int,
)

sealed interface PlaybackEvent {
    data class Message(val text: String) : PlaybackEvent      // 走 Snackbar
    data class Unplayable(val reason: String) : PlaybackEvent
}
```

```kotlin
// 两个小 port，测试可替换
fun interface TrackMediaItemFactory {
    fun create(track: Track): MediaItem?   // null = 算不出 URL
}

interface PlaybackGate {
    fun isPlayable(track: Track): Boolean
    fun unplayableReason(track: Track): String?
}
```

生产 adapter：
- `MediaControllerPlaybackRemote` 包 `MediaController`。
- `TrackMediaItemFactory` 由 `viewModel::streamUrl` + `Track.toPlaybackMetadata()` 组成。
- `PlaybackGate` 由 `viewModel::isPlayable` / `viewModel::unplayableReason` 组成。

---

## 5. 执行步骤

### Step 0 — 基线 + 测试基建（不改生产代码）

> **已完成 2026-11-08**。基线：63 套 / 731 例（本步开始时 61 套 / 727 例）全绿。
> **Fake 没有按原文在本步建，挪到 Step 2 的 RED 阶段**：`FakePlaybackRemote` /
> `FakeTrackMediaItemFactory` / `FakePlaybackGate` 要实现的接口此刻还不存在，写出来编译不过；
> 且 §4 自己写明接口形状「slice 2 再定稿」——先建 Fake 等于把没定稿的形状抄两遍。

**目标**：确认改动前全绿，准备好共享 Fake。

**动作**
1. 跑全量单测，记录结果。
2. 新建 `app/src/test/java/com/example/pulsar/playback/PlaybackTestSupport.kt`，放：
   - `FakePlaybackRemote`（记录调用、可配置连接/快照/命令返回值）
   - `FakeTrackMediaItemFactory`
   - `FakePlaybackGate`
3. 不碰生产文件。

**验证**
```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'
"$GRADLE" --no-daemon :app:testDebugUnitTest
```

**验收**：全量测试通过；新文件只有 Fake，没有行为断言。

**回退**：删掉新测试文件即可。

---

### Step 1 — 抽纯函数：MediaItem 构造 + 队列操作

> **已完成 2026-11-08**：全量 731 例全绿，`assembleDebug` 通过。与原文的差异：
>
> - 新增 `PlaybackMediaItems.kt`（`Track.toPlaybackMetadata()` + `buildMediaItem(track, url)`）、
>   `PlaybackQueueOps.kt`（`insertAfterCurrentIndex` / `removeIdsFromQueue` / `applyOrderToPositions`），
>   全部 `internal`。测试：`PlaybackMediaItemsTest`、`PlaybackQueueOpsTest`（4 例）。
> - `removeIdsFromQueue` 收 `List<String>`（媒体 id 列表），由调用方拍队列快照；不依赖 Media3 对象。
> - `applyOrderToPositions` 的插入位置**夹在合法范围内**：`moveMediaItem` 对越界索引是抛异常、
>   不是自动夹紧。生产路径（`orderedIds` 是队列的一个排列）下夹紧是空操作，与旧行为逐字一致。
> - **JVM 单测测不到 URI / 封面 tag / extras**：桩化 android.jar 下 `Uri.parse` 返回 null，
>   Media3 因此不建 `localConfiguration`（URI 与 tag 都挂在它下面），`Bundle` 读写也是桩。
>   这三项是逐字传递、逻辑为零，靠真机验收清单覆盖（封面显示、换歌响度增益）。
> - **待真机**：基本播放一遍（点歌、切歌、拖拽重排队列、删除队列项）。

**目标**：先把能纯测的逻辑从 `MainActivity.kt` 拿出来，不改调用方。

**改哪些文件**
- 新增 `app/src/main/java/com/example/pulsar/playback/PlaybackMediaItems.kt`
  - `Track.toPlaybackMetadata()`（从 `MainActivity.kt:5003` 搬）
  - `buildMediaItem(track, url): MediaItem`（从 `playTracks` 里的 Builder 搬）
- 新增 `app/src/main/java/com/example/pulsar/playback/PlaybackQueueOps.kt`
  - `insertAfterCurrentIndex(currentIndex, size): Int`
  - `removeIdsFromQueue(items, ids, currentIndex): List<Int>`（倒序索引，跳过正在播）
  - `applyOrderToPositions(currentIds, orderedIds): List<Pair<Int, Int>>`（纯映射，供 adapter 逐条 move）
- `MainActivity.kt` 改为调用新函数；行为不变。

**TDD 切片**
1. RED：`PlaybackMediaItemsTest`：metadata 的 title/artist/album/extras 正确；URL 为空返回 null。
2. GREEN：实现。
3. RED：`PlaybackQueueOpsTest`：插入位置夹紧；删除跳过当前项；重排映射稳定。
4. GREEN：实现。
5. 更新 `MainActivity` 调用点；旧测试全绿。

**验证**
```bash
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.playback.*'
"$GRADLE" --no-daemon :app:assembleDebug
```

**验收**：构建通过；播放/入队/删除/重排行为与改前一致（真机点一遍基本播放即可，完整回归留到 Step 3）。

**回退**：恢复 `MainActivity` 调用点，保留新纯函数文件（不影响行为）。

---

### Step 2 — 引入 `PlaybackRemote` + `PlaybackSession`（连接 / 状态 / 轮询）

**目标**：把 `rememberMediaController` 的 229 行搬进 module；UI 只收状态 + 发 intent。队列写操作仍在旧路径，先不动。

**改哪些文件**
- 新增 `app/src/main/java/com/example/pulsar/playback/PlaybackRemote.kt`
- 新增 `app/src/main/java/com/example/pulsar/playback/MediaControllerPlaybackRemote.kt`
- 新增 `app/src/main/java/com/example/pulsar/playback/PlaybackSession.kt`
- 新增 `app/src/main/java/com/example/pulsar/playback/PlaybackUiState.kt`
- `MainActivity.kt`：
  - 删除 `rememberMediaController` 里的 13 个 `mutableStateOf` 与 500ms 轮询；
  - 换成 `rememberPlaybackSession(controllerFuture, mediaItems, gate, volumeKeys)`；
  - `ControllerState` 暂时保留为 `PlaybackUiState` 的映射（或直接替换，取决于改动量）；
  - `adjustDeviceVolume` / `controllerStatus` / `friendlyPlaybackError` / `usbExclusiveStateLabel` 搬到 module 或保留为纯 helper。
- `SettingsContent` / `MiniPlayerBar` / `NowPlayingScreen`：仍读原来的状态字段，先不改签名。

**实现要点**
- 500ms 轮询放进 `PlaybackSession` 的 `scope`，不挂 Main dispatcher；`in-flight` 守卫保留。
- 自定义命令解析 `SessionResult.extras` 的逻辑只在 module 里。
- `ForegroundVolumeKeys` 的 snapshot/sink 绑定保留在 composable 包装层（它需要 Activity 对象），但 `adjustDeviceVolume` 的下发改成 `session.dispatch(AdjustVolume(step))`。
- 连接失败 / 服务不可用映射成 `PlaybackUiState.status`，不抛异常。

**TDD 切片**
1. RED：`PlaybackSessionTest` 连接成功 → `isConnected=true`、status 正确。
2. RED：轮询读到 position/duration/isPlaying/queue → state 更新。
3. RED：USB 命令成功/失败 → `usb.state` / `usb.error` 更新；in-flight 时不并发发第二条。
4. RED：静音开关命令成功 → `silenceSkipEnabled` 更新。
5. RED：`close()` 后不再轮询、不再发命令。
6. GREEN：实现。
7. 删除旧轮询代码，测试全绿。

**验证**
```bash
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.playback.PlaybackSessionTest'
"$GRADLE" --no-daemon :app:assembleDebug
```

**验收**
- 迷你条：播放/暂停、进度、时长、切歌正常。
- 设置页：USB 独占开关、USB 音量、静音跳过正常。
- 全屏页：播放状态、进度正常。
- 真机点一遍；旧测试全绿。

**回退**：恢复 `rememberMediaController` 旧实现（保留新文件不接线）。

---

### Step 3 — 队列与播放意图全部走 module

**目标**：删掉 UI 里所有直接 `MediaController` 写操作；`playbackMode` 进 module。

**改哪些文件**
- `MainActivity.kt`：
  - `playTracks` → `session.dispatch(Play(tracks, startId))`
  - `onPlayTrack` 的离线守门 → 交给 `PlaybackGate` + `PlaybackEvent.Unplayable`；UI 只收集事件弹 Snackbar
  - `onAddToQueue` → `session.dispatch(AddToQueue(track))`
  - `removeFromQueue` → `session.dispatch(RemoveFromQueue(ids))`
  - `applyQueueOrder` 删除 → `session.dispatch(Reorder(queue))`
  - `playbackMode` 删除 → 读 `state.mode`，按钮发 `SetMode` / `CycleMode`
- `MiniPlayerBar`：
  - 删除本地 `queue` 副本？**先保留**（拖拽需要本地顺序），但 drag stop 只发 `Reorder`；删除按钮发 `RemoveFromQueue`；播放/暂停/seek/next/previous 全部走 intent。
- `NowPlayingScreen`：同 MiniPlayerBar；删除 `applyQueueOrder` 调用，改 `Reorder`。
- `PlaybackSession`：实现 `Play` / `AddToQueue` / `RemoveFromQueue` / `Reorder` / `SeekTo` / `Next` / `Previous` / `TogglePlayPause` / `SetMode` / `CycleMode` / `AdjustVolume`。

**行为决策**
- `Play` 仍然先过 gate：不可播的曲目从队列里筛掉；点的那首不可播 → 只发 `Unplayable`，不写队列。
- `AddToQueue`：正在播的曲目之后插入；队列为空时 set + prepare + play。
- `RemoveFromQueue`：倒序摘，跳过当前项（保留旧语义）。
- `Reorder`：仍逐条 `move(from, to)`，与旧 `applyQueueOrder` 一致。
- `SetMode`：写入 `PlaybackUiState.mode`，并应用到播放器。

**TDD 切片**
1. RED：`Play` 过滤 gate 不可播项；点的那首不可播 → 发 `Unplayable` 且不 setQueue。
2. RED：`AddToQueue` 空队列/非空队列的插入位置。
3. RED：`RemoveFromQueue` 跳过当前项、倒序删除。
4. RED：`Reorder` 产生正确的 move 序列。
5. RED：`SetMode` 更新 state；`CycleMode` 顺序循环 / 单曲 / 随机。
6. GREEN：实现。
7. 删除 MainActivity 旧 helper，更新调用点。

**验证**
```bash
"$GRADLE" --no-daemon :app:testDebugUnitTest
"$GRADLE" --no-daemon :app:assembleDebug
```
真机按 §8 全清单走一遍。

**验收**：全清单通过；`grep -n "MediaController" MainActivity.kt` 只剩 `rememberPlaybackSession` 的 future 解析，不再有队列写操作。

**回退**：恢复旧 `playTracks` / `onAddToQueue` / `removeFromQueue` / `applyQueueOrder`（Step 2 的 session 仍可保留，只是未接线）。

---

### Step 4 — 清理 + 文档 + 删除测试

> **2026-11-09 更新**：#2 / #3 已经把动作 1 的四个符号全部删掉了（`ControllerState` / `queueSnapshot` /
> `applyQueueOrder` / `playTracks` 在 `MainActivity.kt` 里各 0 处）。动作 2 的 grep 也已经干净。
> **剩下的只有动作 3、4、5、6（文档 + 回归）**。开工前先跑一遍 grep 确认没有再冒出来。

**目标**：把旧形状彻底删掉，确认没有浅层残留。

**动作**
1. ~~删除不再使用的 `ControllerState`、旧的 `queueSnapshot`、`applyQueueOrder`、`playTracks`、`toPlaybackMetadata` 旧副本。~~（#2/#3 已完成）
2. `grep -rn "controllerState.controller"` 确认没有 UI 直接操作控制器；如果还有，逐个改成 intent 或明确记录为例外。
3. 更新 `CLAUDE.md` 的播放章节：加 `PlaybackSession` / `PlaybackRemote` / `PlaybackUiState` 的职责与红线。（#3 已完成）
4. 更新 `CONTEXT.md`：加 **播放会话（PlaybackSession）**、**播放 remote（PlaybackRemote）** 两个术语；说明「队列真相在 session，不在两份 UI 本地副本」。（已加，但**「队列单一真相」还没做**，见 #28）
5. 跑全量测试 + `assembleDebug`；真机回归。
6. 跑一次 `git diff` 自查：没有无关格式化、没有删用户数据、没有改 ADR。

**验收**
- 全量测试通过。
- `MainActivity.kt` 行数下降（目标：至少少 400 行）。
- 没有 UI 直接 `controller.setMediaItems/removeMediaItem/moveMediaItem/seekTo...`。
- 真机清单全过。

**回退**：Step 4 只删旧代码，回退即恢复旧 helper（但通常不需要）。

---

## 6. 每个 Step 的可复制开工提示

### Step 0
```
按 docs/playback-session-refactor.md 的 Step 0 执行：先跑全量单测记录基线，然后新建
app/src/test/java/com/example/pulsar/playback/PlaybackTestSupport.kt，放 FakePlaybackRemote /
FakeTrackMediaItemFactory / FakePlaybackGate。不改生产代码。完成后报告基线与新增文件。
```

### Step 1
```
按 docs/playback-session-refactor.md 的 Step 1 执行：TDD 抽 PlaybackMediaItems.kt 与
PlaybackQueueOps.kt，把 MainActivity.kt 的 toPlaybackMetadata / MediaItem Builder / 队列增删排
改成调用纯函数。行为不变。先写失败测试再实现。完成后跑 playback 测试与 assembleDebug。
```

### Step 2
```
按 docs/playback-session-refactor.md 的 Step 2 执行：新增 PlaybackRemote /
MediaControllerPlaybackRemote / PlaybackSession / PlaybackUiState，把 rememberMediaController
的连接、监听、500ms 轮询、USB/静音自定义命令搬进去。UI 签名先不动。先写 PlaybackSessionTest
（连接、轮询、命令成功/失败、in-flight、close），再实现。完成后跑测试 + assembleDebug + 真机
点迷你条/设置页/全屏页。
```

### Step 3
```
按 docs/playback-session-refactor.md 的 Step 3 执行：把 playTracks / onPlayTrack /
onAddToQueue / removeFromQueue / applyQueueOrder / playbackMode 全部改成 PlaybackSession 的
intent；MiniPlayerBar 与 NowPlayingScreen 不再直接写 MediaController。先写 PlaybackSessionTest
的新切片（Play 过滤、AddToQueue、Remove、Reorder、SetMode/CycleMode），再实现。完成后跑全量
测试 + assembleDebug + 真机全清单。
```

### Step 4
```
按 docs/playback-session-refactor.md 的 Step 4 执行：删除旧 helper 与 ControllerState 残留，
grep 确认 UI 不再直接写 MediaController，更新 CLAUDE.md 播放章节与 CONTEXT.md 术语，跑全量
测试 + assembleDebug + 真机回归，最后给 git diff 自查报告。
```

---

## 7. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| `PlaybackRemote` 包得太宽，变成另一个浅 interface | 提取后只是多一层转发 | 只暴露 session 真正需要的动作；`snapshot()` 一次拿状态，不逐个 getter |
| 500ms 轮询迁进 module 后，state 更新频率仍让主界面重组 | 性能不回退但也没改善 | `StateFlow` + 局部收集；`playerDockHeight` 的 State 模式保留；后续可拆 `position` 单独 flow |
| `playbackMode` 进 module 但仍随 composition 重建 | 转屏仍重置 | Step 4 评估 Activity 级/ViewModel 持有；若不做，明确写进文档 |
| 队列本地副本（迷你条 / 全屏页）仍在 | 三方真相没有完全消除 | Step 3 先保留拖拽本地副本，只把回写统一到 `Reorder`；单一真相另开 ticket |
| 离线 gate 注入 module | 播放模块依赖 ViewModel 语义 | 用 `PlaybackGate` port，生产适配器只是两个方法引用；测试用 Fake |
| `MediaController` 的 listener 线程 | state 更新线程不安全 | 所有 state 写入走 session 的 scope；adapter 只做转发 |

**待确认（开工前回答，或直接按默认执行）**
1. D6 生命周期：slice 2 先用 `remember`，还是这一步就直接做 Activity 级/ViewModel 持有？
2. `playbackMode` 是否要跨进程死亡保留（DataStore），还是只要求跨转屏？
3. 队列单一真相是否要在 Step 3 一起做，还是另开 ticket？
4. 是否接受 `PlaybackUiState` 里把 `UsbOutputState` 嵌套（推荐），还是继续平铺 17 个字段？

## 8. 真机验收清单

- [ ] 发现页点每日推荐一首 → 播放，封面/标题正确
- [ ] 歌曲页点一首 → 从该首开始，队列顺序正确
- [ ] 专辑详情 / 播放列表详情 / 搜索结果点一首 → 播放
- [ ] 长按 / 更多菜单「添加到播放队列」→ Snackbar「已添加到播放队列」，队列末尾/当前之后出现
- [ ] 迷你条：播放/暂停、上一首/下一首、进度拖动、时长显示
- [ ] 迷你条展开面板：播放模式循环（顺序 / 单曲 / 随机）
- [ ] 迷你条队列面板：拖拽重排、点行切歌、删除单曲
- [ ] 全屏播放页：打开/收起、封面飞行动画、进度、队列页拖拽/删除、歌词页
- [ ] 设置页：USB 独占开关、USB 音量滑块、静音跳过开关
- [ ] USB 独占开启 + 插 DAC：音量键接管、音量浮窗、暂停/恢复
- [ ] 后台播放 + 通知栏控制
- [ ] 转屏（横竖屏）后播放不中断、状态不丢
- [ ] 杀进程重进：会话恢复、播放服务重连、迷你条状态正确
- [ ] 离线：点未下载的歌 → 提示「这首歌没有下载，联网后才能播放」，不写队列
- [ ] 离线：队列里遗留的未下载歌 → 播放失败逻辑与改前一致

## 9. 命令速查

```bash
# 每次新终端
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

# 全量单测
"$GRADLE" --no-daemon :app:testDebugUnitTest

# 单类
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.playback.PlaybackSessionTest'

# 构建
"$GRADLE" --no-daemon :app:assembleDebug

# 安装（无线调试端口每次可能变，先问用户）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" connect <ip>:<port>
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

## 10. 完成后

- 按 `docs/agents/issue-tracker.md` 的约定，把剩余候选拆成 GitHub Issues（`gh` CLI）。
- 如果 Step 3 决定「队列单一真相」另开 ticket，在 issue 里引用本文 §7。
- 如果 `PlaybackSession` 的接口定型，补一条 ADR：**播放队列真相在 PlaybackSession，不在 UI 本地副本**。
