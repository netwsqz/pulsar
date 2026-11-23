# QQ 音乐下载服务（PC 侧）

`qqmusic_service.py` 是跑在电脑上的 FastAPI 服务，包装 `E:\music` 的下载/解密/打标签能力，向局域网内的 Pulsar 暴露 HTTP 接口。

## 启动

双击 `start.bat`（或 `python qqmusic_service.py`）。服务监听 `0.0.0.0:8900`。

电脑曲库接口使用 Starlette 的 `FileResponse` 提供 Range；**Starlette 至少为 0.49.1**（含 Range 安全修复），搭配 FastAPI 0.121.3 或更新的兼容版本。沿用原有启动环境，若依赖版本过低，在该环境安装：

```powershell
python -m pip install "fastapi>=0.121.3" "starlette>=0.49.1"
```

QQ 音乐导入需要 QQ 凭证；若 `E:\music\qqmusic_cred.json` 缺失/失效，需要 QQ 音乐客户端正在运行且已登录，调 `POST /api/credential/retry` 提取凭证（客户端之后可以关掉）。**电脑曲库的配对和播放不用 QQ 凭证或 Jellyfin，也不需要 ffmpeg 转码。**

## 接口一览

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/health` | GET | 服务健康 + ffmpeg + 凭证状态 + 曲库索引（`library.ready` / `library.count`） |
| `/api/search?q=<关键词>&limit=20` | GET | 搜歌，每项含 `available_qualities`、`in_library`、`downloading` |
| `/api/album/search?q=<关键词>` | GET | 搜专辑 |
| `/api/album/{album_mid}/songs` | GET | 专辑曲目：`songs`（`song_mid` + 歌名 `name` + 歌手 `singer` + `in_library` / `downloading`）、`song_mids` |
| `/api/download` | POST | `{"song_mid": "...", "quality": "flac", "title": "...", "artist": "..."}` → `{"job_id": "...", "skipped": false}` |
| `/api/download/album` | POST | `{"album_mid": "...", "quality": "flac"}` → 整张逐首入队；带 `"song_mids": ["..."]` 则只入队选中的曲目（App 的「选曲下载」） |
| `/api/jobs` | GET | 任务列表（最新 100 条） |
| `/api/jobs/{job_id}` | GET | 单任务：`queued/running/done/failed` + `error` + `final_path` |
| `/api/credential/retry` | POST | 凭证过期后重提（需 QQ 音乐客户端运行中） |
| `/api/library/tracks` | GET | 访问密钥：`{"tracks":[{"id":"…","title":"…","artist":"…","album":"…"}]}`，不返回文件路径 |
| `/api/library/tracks/{id}/audio` | GET | 访问密钥：读取原文件字节，支持 HTTP Range |
| `/api/library/delete` | POST | 🔒 `{"paths": [...]}` → `{"deleted": [...], "failed": [{"path", "error"}]}`（挪进回收站） |
| `/api/library/rename` | POST | 🔒 `{"path", "title"?, "artist"?, "album"?}` → `{"ok", "path"}`（改**内嵌标签**） |
| `/api/library/recycle` | GET | 🔒 `{"files": [{"rel_path", "size", "deleted_at"}], "total_size"}` |
| `/api/library/recycle/restore` | POST | 🔒 `{"paths": [...]}` → `{"restored": [...], "failed": [...]}` |
| `/api/library/recycle/purge` | POST | 🔒 `{"purged": n}`（**唯一不可逆**） |

🔒 = 需要文件操作密钥，请求头带 `X-Pulsar-Key`；缺密钥或对不上回 **401**。

## 电脑曲库配对

- 手动启动现有服务后，在电脑上打开 `server/.library_access_key`，在 App 的「电脑曲库」填写 `http://<电脑局域网 IP>:8900` 和文件里的访问密钥。访问密钥自动生成、重启复用，**只在启动日志提示文件位置，不打印它的值**；与文件操作密钥不同。
- 两条只读接口都需 `Authorization: Bearer <访问密钥>`，**不能把密钥放在 URL**。缺少或填错密钥回 `401`，索引未就绪或根目录不可读回 `503`，曲库确实为空时列表回 `200` 和 `{"tracks":[]}`；若密钥文件无法读取或创建，仅这两条接口回 `500`。请检查服务目录的权限及文件是否完整，服务不会覆盖已有密钥。
- 曲目 ID 是 64 位小写十六进制；文件路径不变时，重启、重扫或改内嵌标签不会改变 ID。音频按 ID 读取曲库内的原文件，未知或失效 ID 回 `404`，可满足的 Range 回 `206`，超出文件的范围回 `416`。曲库里的联接和符号链接不入索引。
- 局域网 HTTP **不加密**。只在可信家庭网络使用，不做端口转发，也不要把服务开放到公网。

## 文件操作密钥

服务监听 `0.0.0.0` 且没有账号体系，所以文件管理那五个接口单独配了一把锁（`library_files.py` 是**全服务唯一会动用户文件**的模块）。

- 首次启动自动生成，存在 `server/.file_ops_key`（已 gitignore），**启动日志会打印密钥值**，App 设置页填一次。
- **只锁上表里带 🔒 的五个**。搜索、导入、任务查询不挂这把密钥；电脑曲库用独立的访问密钥。
- 密钥文件读不出来（目录只读等）时退化成临时密钥，服务照常起，只是重启后要重填。
- 删 / 改 / 恢复成功后会**立刻重扫曲库索引**，否则删完想重下会被去重挡下来（清空回收站不动曲库，不重扫）。

## 曲库去重

服务后台维护一份 `E:\file\Te_Music` 的曲库索引（`library_index.py`，**只读**，启动时扫一遍、之后每 3 分钟对比目录快照，有变化才重读标签）。电脑曲库和 QQ 音乐导入共用这份索引，优先读内嵌标签（`TITLE`/`ARTIST` 等），缺歌名才回退解析文件名。

- 搜索与专辑曲目接口给每首歌带 `in_library`（已在库）与 `downloading`（任务队列里 `queued`/`running`）。
- 下载**默认跳过已在库的**：单曲回 `{"job_id": "", "skipped": true}`（字段形状与正常响应一致，老客户端不会解析失败）；整张下载回 `count`（入队数）+ `skipped_count`（跳过数）。
- **要重下就带 `force: true`**（单曲与整张都支持）。整张下载时显式传了 `song_mids` 的，服务端按勾选原样入队、不自动跳过——勾选本身就是强制。
- **判定不出来一律按「没有」处理**：索引未就绪（服务刚起的那十几秒，`/api/health` 的 `library.ready=false`）、歌名读不出、索引抛异常——一律照常下载、不报错。宁可漏判，不误判挡人。
- 服务自己下载成功的歌会**立刻**并入索引，不等下一次周期扫描。

## 关键行为

- **落盘目录 `E:\file\Te_Music`**：Jellyfin 音乐库已开启实时监控，文件落盘即自动入库，Pulsar 刷新曲库即可看到。
- **串行下载**：单工作线程 + 队列，每首间隔 1 秒（防风控）。同 song_mid+quality 的活跃任务自动去重。
- **凭证过期判定**：连续 3 次「拿不到下载链接」→ 服务进入 `credential_expired`，App 端会提示；用户打开 QQ 音乐客户端登录后点「重试提取凭证」恢复。
- **不改 E:\music 代码**：`process_song` 的 print 输出被捕获存进 job 的 `log` 字段，最终路径从 `-> ...` 行解析。
- **凭证敏感**：cookie 只在内存与 `E:\music\qqmusic_cred.json`，接口不回传 cookie。

## 防火墙

首次启动若 Windows 弹防火墙询问，允许「专用网络」即可；或手动放行：

```powershell
New-NetFirewallRule -DisplayName "Pulsar QQ 下载服务" -Direction Inbound -LocalPort 8900 -Protocol TCP -Action Allow
```
