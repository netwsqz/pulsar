# 06 — App：设置页回收站 + 密钥输入

**What to build:** 看得见回收站（有什么、占多大）、能把误删的捞回来、能手动清空；以及把文件操作密钥填进去。

**Blocked by:** 03

**Status:** resolved

### 回收站（放 `LibrarySection.Settings`）

- [x] 新增「回收站」区块，进页面时拉一次 `GET /api/library/recycle`
- [x] 显示总占用（如「12 个文件 · 340 MB」）与文件列表（相对路径 + 单文件大小）
- [x] 每条一个「恢复」按钮 → 调接口 → 成功后从列表移除、提示"已恢复到原位置"
- [x] 「清空回收站」按钮 + **二次确认，文案里写明不可恢复**（全功能里唯一真删数据的操作）
- [x] 服务不可达时显示明确提示（"电脑上的服务没开"），不是转圈或崩掉
- [x] 空回收站显示"回收站是空的"

### 密钥输入

- [x] 放 `QQDownloadScreen` 里服务地址的**旁边**（同一个服务，配在同一处；见 `CONTEXT.md`「文件操作密钥」）
- [x] 密码样式的输入框 + 一个"显示"切换，保存进 `QQDownloadPreferences`
- [x] 旁边给一行说明：密钥在电脑上服务启动日志里，或 `server/.file_ops_key` 文件里
- [x] 填错时不必在这里报错——删除/改名时会收到 `Unauthorized` 提示

### 测试

- [x] ViewModel 层覆盖：回收站列表加载成功/失败、恢复成功后条目被移除、清空后列表变空、密钥保存进 DataStore

## Comments

## Comments

- 回收站放进 `MainActivity.kt` 的 `RecycleBinSection`（设置页底部，`SettingsContent` 新增 `state`/`viewModel` 两个参数）；每次进设置页都重拉一次（删除/恢复随时改内容，不缓存）。
- 总占用不单独存状态，由文件列表 `sumOf { it.size }` 现算；恢复成功直接从列表滤掉该条。
- 密钥卡放 `QQDownloadScreen.kt` 的 `FileOpsKeyCard`（音质 chip 下面，始终显示，不挂在"未连接"分支里）；输入与已存值分开存（`fileOpsKeyInput`/`fileOpsKey`），内容没变时保存按钮置灰并显示「已保存」。
- 清空确认框按钮文字「清空（不可恢复）」，正文写明文件数与不可恢复。
- 测试新增：回收站加载成功/失败、恢复移除条目、清空变空（`JellyfinViewModelTest`）；密钥启动载入+保存落盘（`QQDownloadViewModelTest`）。全套绿。
