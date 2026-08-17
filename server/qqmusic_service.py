"""
qqmusic_service.py — QQ 音乐下载 HTTP 服务（播放器项目侧）
==========================================================

包装 E:\music 的现成函数（qmc2 / qqmusic_downloader / qqmusic_workflow），
向局域网内的 Pulsar 播放器暴露搜索 / 专辑 / 下载任务接口。

约定（来自 E:\music\交接文档.md，2026-08-17 核实）：
  - 单工作线程 + queue.Queue 串行执行，绝不并发调 process_song
  - 每首之间 sleep 1 秒防风控
  - process_song 返回 bool 不返回路径；从捕获的 print（"    -> ..." 行）解析最终路径
  - 连续 3 次「拿不到下载链接」判定凭证过期，等待客户端手动触发重新提取
  - 不修改 E:\music 下的任何文件

启动:  双击 start.bat（或 python qqmusic_service.py）
依赖:  pip install fastapi uvicorn requests mutagen frida==16.7.18 + ffmpeg 在 PATH
"""

from __future__ import annotations

import contextlib
import io
import logging
import queue
import re
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

# ---- E:\music 注入 ----
MUSIC_ROOT = Path(__file__).resolve().parent.parent.parent / "music"
if not MUSIC_ROOT.exists():
    # 直接放 E:\music 旁边时的兜底
    MUSIC_ROOT = Path("E:/music")
sys.path.insert(0, str(MUSIC_ROOT))

import qmc2  # noqa: E402
import qqmusic_downloader as dl  # noqa: E402
from qqmusic_workflow import find_ffmpeg  # noqa: E402

from fastapi import FastAPI, HTTPException  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from pydantic import BaseModel  # noqa: E402

# ---- 常量 ----
LIBRARY_ROOT = Path("E:/file/Te_Music")  # Jellyfin 音乐库目录（实时监控已开启，落盘即入库）
QUALITIES = ("hires", "flac", "320", "128")
CREDENTIAL_FAIL_LIMIT = 3  # 连续 N 次「拿不到下载链接」判定凭证过期
SONG_MID_RE = re.compile(r"^00[0-9A-Za-z]{10,14}$")
# process_song 成功时 print 的归档行："    -> 相对/路径.flac  (含歌词)"
FINAL_PATH_RE = re.compile(r"^\s{4}->\s+(\S+.*?)\s*(?:\s+\(含歌词\))?\s*$", re.MULTILINE)

log = logging.getLogger("qqmusic_service")

# ---- 全局状态 ----
app = FastAPI(title="Pulsar QQ 音乐下载服务", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

jobs: dict[str, dict] = {}
job_queue: "queue.Queue[str]" = queue.Queue()
state_lock = threading.Lock()

_service_state: dict = {
    "ffmpeg_ready": False,
    "ffmpeg_error": None,
    "credential_expired": False,   # True = 等待用户打开 QQ 音乐客户端后点重试
    "consecutive_no_link": 0,
}
_auth_cache: Optional[dict] = None
_auth_lock = threading.Lock()


# ─── 凭证 ─────────────────────────────────────────────────

def get_auth(force_refresh: bool = False) -> dict:
    """取凭证：优先内存缓存 → qqmusic_cred.json → 从进程提取（需客户端运行）。

    force_refresh=True（凭证过期的恢复路径）：跳过缓存和磁盘文件直接从进程提取。
    磁盘上的旧 cookie 可能已过期（load_auth 只查键存在性不验证有效性），
    重提必须拿到新鲜 cookie 才有意义。
    """
    global _auth_cache
    with _auth_lock:
        if not force_refresh and _auth_cache:
            return _auth_cache
        if not force_refresh:
            auth = qmc2.load_auth()
            if auth:
                _auth_cache = auth
                return auth
        res = qmc2.extract_cookie_from_process()
        if not res.get("ok"):
            raise CredentialError(res.get("error", "提取失败"))
        auth = {"cookie": res["cookie"], "uin": res["uin"]}
        qmc2.save_auth(auth)
        _auth_cache = auth
        # 重提成功即解除凭证过期状态（与 worker 的写入统一走 state_lock）。
        with state_lock:
            _service_state["credential_expired"] = False
            _service_state["consecutive_no_link"] = 0
        return auth


class CredentialError(RuntimeError):
    """凭证缺失且无法从进程提取（需要用户打开并登录 QQ 音乐客户端）。"""


# ─── 下载工作线程（单线程串行） ────────────────────────────

def _clean_stage_dir() -> None:
    """启动时清空暂存目录（只在无任务时调用一次）。"""
    stage = MUSIC_ROOT / "_download_stage"
    if not stage.exists():
        return
    for f in stage.iterdir():
        if f.is_file():
            with contextlib.suppress(Exception):
                f.unlink()


def _parse_final_path(captured: str, output_root: Path) -> Optional[str]:
    """从 process_song 捕获的 print 里解析最终归档路径。"""
    m = FINAL_PATH_RE.search(captured)
    if not m:
        return None
    rel = m.group(1).strip()
    final = (output_root / rel).resolve()
    # 防御：确认解析出的路径真的存在且在曲库内
    if final.exists() and output_root.resolve() in final.parents:
        return str(final)
    return None


def _worker() -> None:
    global _service_state
    # 启动即探测 ffmpeg（问题暴露在启动日志，别等第一个下载任务才炸——交接文档坑 10）。
    ffmpeg: Optional[str] = None
    try:
        ffmpeg = find_ffmpeg()
        _service_state["ffmpeg_ready"] = True
    except RuntimeError as e:
        _service_state["ffmpeg_error"] = str(e)
        log.error("ffmpeg 不可用: %s", e)
    _clean_stage_dir()

    while True:
        job_id = job_queue.get()
        with state_lock:
            job = jobs.get(job_id)
        if job is None:
            continue
        if job["status"] == "cancelled":
            continue

        if not _service_state["ffmpeg_ready"]:
            try:
                ffmpeg = find_ffmpeg()
                _service_state["ffmpeg_ready"] = True
                _service_state["ffmpeg_error"] = None
            except RuntimeError as e:
                _service_state["ffmpeg_error"] = str(e)
                _finish(job_id, "failed", error=f"ffmpeg 不可用: {e}")
                continue

        with state_lock:
            job["status"] = "running"
            job["started_at"] = time.time()
        try:
            auth = get_auth()
            # 捕获 process_song 的 print 噪音（进程级替换，但 worker 是唯一写者）
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                ok = dl.process_song(job["song_mid"], job["quality"], auth, LIBRARY_ROOT, ffmpeg)
            captured = buf.getvalue()
            final_path = _parse_final_path(captured, LIBRARY_ROOT) if ok else None
            if ok:
                with state_lock:
                    _service_state["consecutive_no_link"] = 0
                    _service_state["credential_expired"] = False
                _finish(job_id, "done", final_path=final_path, log_text=captured)
            else:
                _note_no_link_failure(job_id, captured)
        except CredentialError as e:
            _finish(job_id, "failed", error=f"凭证缺失: {e}", log_text=None)
        except Exception as e:  # 兜住 NameError 等一切异常（交接文档坑 7）
            _finish(job_id, "failed", error=f"{type(e).__name__}: {e}")
        time.sleep(1)  # 防风控限速（交接文档坑 3）


def _note_no_link_failure(job_id: str, captured: str) -> None:
    """失败处理：识别「拿不到下载链接」并做连续计数 → 凭证过期判定。"""
    no_link = "拿不到下载链接" in (captured or "")
    error = "下载/解密/整理失败（常见：该歌需要 VIP/无版权，或凭证已过期）"
    with state_lock:
        if no_link:
            _service_state["consecutive_no_link"] += 1
            if _service_state["consecutive_no_link"] >= CREDENTIAL_FAIL_LIMIT:
                _service_state["credential_expired"] = True
                error = "凭证可能已过期：请打开 QQ 音乐客户端并登录，然后在 App 里点「重试提取凭证」"
        else:
            _service_state["consecutive_no_link"] = 0
    _finish(job_id, "failed", error=error, log_text=captured)


def _finish(job_id: str, status: str, error: Optional[str] = None,
            final_path: Optional[str] = None, log_text: Optional[str] = None) -> None:
    with state_lock:
        job = jobs.get(job_id)
        if job is None:
            return
        job["status"] = status
        job["finished_at"] = time.time()
        if error is not None:
            job["error"] = error
        if final_path:
            job["final_path"] = final_path
        if log_text is not None:
            job["log"] = log_text[-4000:]  # 截尾，防止日志无限膨胀


def _enqueue(song_mid: str, quality: str, title: Optional[str] = None) -> str:
    if not SONG_MID_RE.fullmatch(song_mid):
        raise HTTPException(400, f"无效的 song_mid: {song_mid!r}")
    if quality not in QUALITIES:
        raise HTTPException(400, f"quality 必须是 {QUALITIES} 之一")
    with state_lock:
        # 去重：同 song_mid+quality 且仍在队列/运行中 → 直接返回已有 job_id
        for jid, j in jobs.items():
            if (j["song_mid"] == song_mid and j["quality"] == quality
                    and j["status"] in ("queued", "running")):
                return jid
        job_id = uuid.uuid4().hex[:12]
        jobs[job_id] = {
            "job_id": job_id, "song_mid": song_mid, "quality": quality,
            "title": title, "status": "queued",
            "created_at": time.time(), "error": None,
            "final_path": None, "log": None,
        }
    job_queue.put(job_id)
    return job_id


# ─── Pydantic 模型 ─────────────────────────────────────────

class DownloadBody(BaseModel):
    song_mid: str
    quality: str = "flac"
    title: Optional[str] = None


class DownloadAlbumBody(BaseModel):
    album_mid: str
    quality: str = "flac"
    album_name: Optional[str] = None


class RetryCredentialBody(BaseModel):
    pass


# ─── 路由 ─────────────────────────────────────────────────

@app.get("/api/health")
def health() -> dict:
    return {
        "ok": True,
        "ffmpeg_ready": _service_state["ffmpeg_ready"],
        "ffmpeg_error": _service_state["ffmpeg_error"],
        "credential_expired": _service_state["credential_expired"],
        "library_root": str(LIBRARY_ROOT),
        "queued": job_queue.qsize(),
        "version": app.version,
    }


@app.get("/api/search")
def search(q: str, limit: int = 20) -> dict:
    q = q.strip()
    if not q:
        return {"songs": []}
    limit = max(1, min(limit, 50))
    auth = _auth_or_503()
    songs = qmc2.search_songs(q, auth["cookie"], str(auth["uin"]), limit=limit) or []
    for s in songs:
        s["available_qualities"] = [
            qz for qz, sz in (
                ("hires", s.get("size_hires") or 0),
                ("flac", s.get("size_flac") or 0),
                ("320", s.get("size_320") or 0),
                ("128", s.get("size_128") or 0),
            ) if sz
        ]
    return {"songs": songs}


@app.get("/api/album/search")
def album_search(q: str, limit: int = 20) -> dict:
    q = q.strip()
    if not q:
        return {"albums": []}
    limit = max(1, min(limit, 50))
    auth = _auth_or_503()
    albums = qmc2.search_albums(q, auth["cookie"], str(auth["uin"]), limit=limit) or []
    return {"albums": albums}


@app.get("/api/album/{album_mid}/songs")
def album_songs(album_mid: str) -> dict:
    if not re.fullmatch(r"00[0-9A-Za-z]{8,16}", album_mid or ""):
        raise HTTPException(400, "无效的 album_mid")
    auth = _auth_or_503()
    songs = _album_song_details(album_mid, auth["cookie"], str(auth["uin"]))
    return {"song_mids": [s["song_mid"] for s in songs]}


def _album_song_details(album_mid: str, cookie: str, uin: str) -> list[dict]:
    """专辑曲目（mid + 歌名）。

    qmc2.get_album_songs 只返回 mid 不带歌名，任务列表会显示成内部 ID；
    E:\music 不许改，所以这里复用同一底层接口把歌名一起取回来
    （请求次数与 get_album_songs 相同，只是多读字段）。
    """
    details = []
    start = 0
    while True:
        result = qmc2._musicu_post(
            {
                "comm": {"cv": 4747474, "ct": 24, "format": "json", "uin": int(uin), "g_tk": 5381},
                "req_1": {
                    "module": "music.musichallAlbum.AlbumSongList",
                    "method": "GetAlbumSongList",
                    "param": {"albumMid": album_mid, "albumID": 0, "start": start, "num": 100},
                },
            },
            cookie,
        )
        if not result:
            break
        data = result.get("req_1", {}).get("data", {})
        song_list = data.get("songList", [])
        if not song_list:
            break
        for item in song_list:
            info = item.get("songInfo", {})
            mid = info.get("mid")
            if mid:
                details.append({
                    "song_mid": mid,
                    "name": info.get("name") or info.get("title") or "",
                })
        start += len(song_list)
        total = data.get("totalNum", 0)
        if (total and start >= total) or len(song_list) < 100:
            break
    return details


def _auth_or_503() -> dict:
    if _service_state["credential_expired"]:
        raise HTTPException(503, "凭证已过期，请打开 QQ 音乐客户端并登录，然后在 App 里点「重试提取凭证」")
    try:
        return get_auth()
    except CredentialError as e:
        raise HTTPException(503, f"凭证缺失: {e}")


@app.post("/api/download")
def download(body: DownloadBody) -> dict:
    job_id = _enqueue(body.song_mid, body.quality, title=body.title)
    return {"job_id": job_id}


@app.post("/api/download/album")
def download_album(body: DownloadAlbumBody) -> dict:
    if not re.fullmatch(r"00[0-9A-Za-z]{8,16}", body.album_mid or ""):
        raise HTTPException(400, "无效的 album_mid")
    if body.quality not in QUALITIES:
        raise HTTPException(400, f"quality 必须是 {QUALITIES} 之一")
    auth = _auth_or_503()
    songs = _album_song_details(body.album_mid, auth["cookie"], str(auth["uin"]))
    if not songs:
        raise HTTPException(504, "未取到专辑曲目（网络或凭证问题）")
    job_ids = [_enqueue(s["song_mid"], body.quality, title=s["name"] or None) for s in songs]
    return {"job_ids": job_ids, "count": len(job_ids), "album_mid": body.album_mid}


@app.get("/api/jobs")
def list_jobs() -> dict:
    with state_lock:
        items = sorted(jobs.values(), key=lambda j: j["created_at"], reverse=True)
        return {"jobs": [dict(j) for j in items[:100]]}


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    with state_lock:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "任务不存在")
        return dict(job)


@app.post("/api/credential/retry")
def retry_credential() -> dict:
    """重提凭证：需要 QQ 音乐客户端正在运行且已登录。"""
    try:
        auth = get_auth(force_refresh=True)
        return {"ok": True, "uin": auth["uin"]}
    except CredentialError as e:
        raise HTTPException(503, f"提取失败: {e}")


@app.on_event("startup")
def _startup() -> None:
    threading.Thread(target=_worker, name="download-worker", daemon=True).start()


if __name__ == "__main__":
    import uvicorn
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    print(f"曲库目录: {LIBRARY_ROOT}")
    print("服务地址: http://0.0.0.0:8900  （手机访问 http://<电脑IP>:8900）")
    uvicorn.run(app, host="0.0.0.0", port=8900)
