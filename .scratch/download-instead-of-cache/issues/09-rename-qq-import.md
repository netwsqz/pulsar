# 09 — QQ 入口改名「QQ 音乐导入」

**What to build:** 把「下载」这个词让给手机上的新功能。QQ 那个入口改叫「QQ 音乐导入」。

**Blocked by:** None — can start immediately.

**Status:** ready-for-human（代码完成；真机看一眼文案即可）

- [x] 设置页入口、二级页标题的显示文案 → 「QQ 音乐导入」（**只改文案，不动枚举名/结构**）
- [x] `LibrarySection.DownloadService` 保留原名（枚举名是内部标识，不改）
- [x] 服务状态卡标题「QQ 下载服务」→「QQ 音乐导入服务」
- [x] 页内文案里「下载任务」「下载音质」保留——局部语境清楚，不用全改
- [x] 代码注释同步改
- [x] `LibrarySectionTest` 里断言文案的注释/测试名同步更新

## Comments

**实现记录（2026-10-25，agent）：**

只改显示文案与注释，结构一行没动：

- `MainActivity.kt` 设置页：分组标题「下载」→「QQ 音乐导入」，卡片标题「下载服务」→「QQ 音乐导入」
- `QQDownloadScreen.kt`：页标题「下载服务」→「QQ 音乐导入」，`ServiceStatusCard` 标题「QQ 下载服务」→「QQ 音乐导入服务」，文件头注释补上「为什么不叫下载」的理由
- `LibrarySection.kt`：`DownloadService` 的 KDoc 与 `TopLevelSections` 的 KDoc 同步
- `LibrarySectionTest.kt`：注释与一个测试名同步（断言没变，本来就是断结构不是断文案）

**没做的：** 没把枚举 `LibrarySection.DownloadService` 改名。它现在叫「下载服务」但显示「QQ 音乐导入」，看起来不一致——但重命名枚举要动 `MainActivity` 里 5 处 `when` 分支，属于与本工单无关的重构，改了会让 code review 看不出重点。保留。
