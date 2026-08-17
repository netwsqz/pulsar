# QQ 音乐下载服务（PC 侧）

`qqmusic_service.py` 是跑在电脑上的 FastAPI 服务，包装 `E:\music` 的下载/解密/打标签能力，向局域网内的 Pulsar 暴露 HTTP 接口。

## 启动

双击 `start.bat`（或 `python qqmusic_service.py`）。服务监听 `0.0.0.0:8900`。

首次启动时若 `E:\music\qqmusic_cred.json` 缺失/失效，需要 QQ 音乐客户端正在运行且已登录，调 `POST /api/credential/retry` 提取凭证（客户端之后可以关掉）。

## 接口一览

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/health` | GET | 服务健康 + ffmpeg + 凭证状态 |
| `/api/search?q=<关键词>&limit=20` | GET | 搜歌，每项含 `available_qualities` |
| `/api/album/search?q=<关键词>` | GET | 搜专辑 |
| `/api/album/{album_mid}/songs` | GET | 专辑全部 song_mid |
| `/api/download` | POST | `{"song_mid": "...", "quality": "flac"}` → `{"job_id": "..."}` |
| `/api/download/album` | POST | `{"album_mid": "...", "quality": "flac"}` → 整张逐首入队 |
| `/api/jobs` | GET | 任务列表（最新 100 条） |
| `/api/jobs/{job_id}` | GET | 单任务：`queued/running/done/failed` + `error` + `final_path` |
| `/api/credential/retry` | POST | 凭证过期后重提（需 QQ 音乐客户端运行中） |

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
