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
import requests
import secrets
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

from fastapi import Depends, FastAPI, Header, HTTPException  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from pydantic import BaseModel  # noqa: E402

from library_files import LibraryFiles, LibraryFilesError  # noqa: E402
from library_index import DEFAULT_LIBRARY_ROOT, LibraryIndex  # noqa: E402

# ---- 常量 ----
LIBRARY_ROOT = DEFAULT_LIBRARY_ROOT  # Jellyfin 音乐库目录（实时监控已开启，落盘即入库）
QUALITIES = ("hires", "flac", "320", "128")
CREDENTIAL_FAIL_LIMIT = 3  # 连续 N 次「拿不到下载链接」判定凭证过期
NO_LINK_MARKER = "拿不到下载链接"  # process_song 判定「链接为空」时打印的固定文案
SONG_MID_RE = re.compile(r"^00[0-9A-Za-z]{10,14}$")
# process_song 成功时 print 的归档行："    -> 相对/路径.flac  (含歌词)"
FINAL_PATH_RE = re.compile(r"^\s{4}->\s+(\S+.*?)\s*(?:\s+\(含歌词\))?\s*$", re.MULTILINE)
LEGACY_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
SEARCH_HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://y.qq.com/"}

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
_last_refresh_error: Optional[str] = None

# 曲库索引：后台线程扫，请求只读内存。构造不碰磁盘，首次扫描在 startup 里发起。
_library_index = LibraryIndex(LIBRARY_ROOT)


# ─── 文件操作密钥 ─────────────────────────────────────────
#
# 服务监听 0.0.0.0 且原本没有任何鉴权——文件管理上线前，局域网里谁都能删曲库。
# 这把锁**只锁危险接口**（删除/改名/恢复/清空回收站），搜索和下载一律不挂：
# 哪天密钥对不上，用户至少还能听歌下歌，不至于连服务都用不了。

KEY_FILE = Path(__file__).resolve().parent / ".file_ops_key"


def _load_or_create_key() -> str:
    """密钥：文件里有就用，没有就生成一个写进去。

    写不进去（目录只读、被占用）不能把服务带崩——搜索下载不该被这把锁连累。
    退化成每次重启换一把的内存密钥，危险接口照样是锁着的。
    """
    try:
        if KEY_FILE.exists():
            key = KEY_FILE.read_text(encoding="utf-8").strip()
            if key:
                return key
        key = secrets.token_urlsafe(24)
        KEY_FILE.write_text(key, encoding="utf-8")
        return key
    except OSError as e:
        log.warning("文件操作密钥读写失败，本次运行改用临时密钥: %s", e)
        return secrets.token_urlsafe(24)


_file_ops_key = _load_or_create_key()


def require_key(x_pulsar_key: Optional[str] = Header(None)) -> None:
    """危险接口的锁。App 在设置页填一次，之后每个请求带上 `X-Pulsar-Key`。"""
    given = (x_pulsar_key or "").encode("utf-8")
    # 按字节比：HTTP 头能塞进非 ASCII 字符，compare_digest 遇到 str 里的
    # 非 ASCII 会直接抛 TypeError，那会把 401 变成 500。
    if not given or not secrets.compare_digest(given, _file_ops_key.encode("utf-8")):
        raise HTTPException(401, "文件操作密钥不正确：请填 App 设置页里那把，"
                                 "或看电脑端服务启动日志打印的密钥")


# ─── 曲库去重 ─────────────────────────────────────────────
#
# 贯穿本区块的一条铁律：**判定不出来一律按「没有」处理**。
# 索引没就绪、歌名读不出、索引自己抛异常——一律当成「库里没有」。
# 这个功能是贴心助手，多下一次只是浪费，误判挡住用户才是灾难。

def _library_has(title: Optional[str], artist: Optional[str] = None) -> bool:
    """曲库里有没有这首歌。

    **歌名和歌手都在才敢下结论。** 缺歌手时只按歌名判是在猜——库里完全可能
    有一首同名但不同人的（真库里 `鬼` 和 `Sorry` 都各有两首不同歌手）。
    猜出来的一律当「没有」：漏判只是多下一次，误判是「我想下，它不让」。
    索引没就绪、索引自己抛异常，同理。
    """
    if not title or not artist:
        return False
    try:
        return _library_index.contains(title, artist)
    except Exception:
        log.exception("查询曲库索引失败: %r / %r", title, artist)
        return False


def _downloading_mids() -> set[str]:
    """正在排队或下载中的 song_mid。"""
    with state_lock:
        return {
            job["song_mid"] for job in jobs.values()
            if job["status"] in ("queued", "running")
        }


def _note_downloaded(final_path: Optional[str]) -> None:
    """刚下完的歌立刻并入索引，不必等下一次周期扫描。

    单独兜异常：索引是贴心助手，它的 bug 不能让下载成功的任务被标成失败。
    """
    if not final_path:
        return
    try:
        _library_index.note_downloaded(Path(final_path))
    except Exception:
        log.exception("并入曲库索引失败: %s", final_path)


# ─── 凭证 ─────────────────────────────────────────────────

def _login_state(auth: dict) -> Optional[bool]:
    """探测凭证是否为有效登录态。

    True = 已登录；False = 明确未登录/凭证失效；None = 网络异常，无法判断。
    用 GetFollowUser（已登录 inner code=40000，未登录/垃圾 cookie=1000；
    其它候选接口对三者返回一致，无法区分）。
    """
    cookie = (auth or {}).get("cookie") or ""
    uin = str((auth or {}).get("uin") or "")
    if not cookie or not uin:
        return False
    try:
        result = qmc2._musicu_post(
            {
                "comm": {"cv": 4747474, "ct": 24, "format": "json", "uin": int(uin), "g_tk": 5381},
                "req_1": {
                    "module": "music.concern.RelationList",
                    "method": "GetFollowUser",
                    "param": {"uin": uin, "start": 0, "num": 1},
                },
            },
            cookie,
        )
    except (ValueError, TypeError, OSError):
        return None
    if not result:
        return None
    code = (result.get("req_1") or {}).get("code")
    if code == 40000:
        return True
    if code is None:
        return None
    return False


def _refresh_credential_from_process() -> Optional[dict]:
    """跳过缓存与磁盘，直接从 QQ 音乐进程重提凭证；失败返回 None。"""
    global _last_refresh_error
    try:
        res = qmc2.extract_cookie_from_process()
    except Exception as e:  # 扫描进程内存可能因权限/客户端版本异常
        _last_refresh_error = f"{type(e).__name__}: {e}"
        log.warning("从进程重提凭证异常: %s", e)
        return None
    if not res.get("ok"):
        _last_refresh_error = str(res.get("error") or "提取失败")
        return None
    auth = {"cookie": res["cookie"], "uin": res["uin"]}
    fresh_state = _login_state(auth)
    if fresh_state is False:
        _last_refresh_error = "从进程提取到的 cookie 不是登录态"
        log.warning("进程重提的凭证仍非登录态，忽略")
        return None
    qmc2.save_auth(auth)
    _last_refresh_error = None
    return auth


def get_auth(force_refresh: bool = False) -> dict:
    """取凭证：优先内存缓存 → qqmusic_cred.json → 从进程提取（需客户端运行）。

    force_refresh=True（凭证过期的恢复路径）：跳过缓存和磁盘文件直接从进程提取。
    磁盘上的旧 cookie 可能已过期（load_auth 只查键存在性不验证有效性），
    重提必须拿到新鲜 cookie 才有意义。

    磁盘凭证加载后会做一次轻量登录态探测（_login_state）：
    明确失效就自动从进程重提并覆盖磁盘，避免用户手工点重试；
    探测不确定（网络异常）时仍按原逻辑使用，避免误伤。
    """
    global _auth_cache
    with _auth_lock:
        if not force_refresh and _auth_cache:
            return _auth_cache
        if not force_refresh:
            auth = qmc2.load_auth()
            if auth:
                if _login_state(auth) is False:
                    fresh = _refresh_credential_from_process()
                    if fresh is not None:
                        auth = fresh
                        with state_lock:
                            _service_state["credential_expired"] = False
                            _service_state["consecutive_no_link"] = 0
                _auth_cache = auth
                return auth
        auth = _refresh_credential_from_process()
        if auth is None:
            raise CredentialError(_last_refresh_error or "无法从 QQ 音乐进程提取有效凭证")
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


def _run_process_song(song_mid: str, quality: str, auth: dict, ffmpeg: str) -> tuple[bool, str]:
    """执行一次 process_song 并捕获其 print 输出。"""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        ok = dl.process_song(song_mid, quality, auth, LIBRARY_ROOT, ffmpeg)
    return ok, buf.getvalue()


def _download_with_recovery(song_mid: str, quality: str, auth: dict,
                            ffmpeg: str) -> tuple[bool, str]:
    """带一次自动重试的下载。

    失败若是「拿不到下载链接」且当前登录态已明确失效（GetFollowUser 返回非登录），
    就从 QQ 音乐进程重提凭证后重试一次，免去用户手动点重试。
    登录态仍有效时说明是该曲目本身无权限，不做无谓重试（避免翻倍请求触发风控）。
    仅重试一次；重提失败或第二次仍失败则原样返回。
    """
    ok, captured = _run_process_song(song_mid, quality, auth, ffmpeg)
    if ok or NO_LINK_MARKER not in captured:
        return ok, captured
    if _login_state(auth) is not False:
        return ok, captured

    fresh = _refresh_credential_from_process()
    if fresh is None:
        return ok, captured
    global _auth_cache
    with _auth_lock:
        _auth_cache = fresh
    log.info("凭证疑似失效，已重提并重试: %s", song_mid)
    ok2, captured2 = _run_process_song(song_mid, quality, fresh, ffmpeg)
    return ok2, captured + "\n[~] 凭证重提后重试一次\n" + captured2


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
            ok, captured = _download_with_recovery(
                job["song_mid"], job["quality"], auth, ffmpeg
            )
            final_path = _parse_final_path(captured, LIBRARY_ROOT) if ok else None
            if ok:
                with state_lock:
                    _service_state["consecutive_no_link"] = 0
                    _service_state["credential_expired"] = False
                _note_downloaded(final_path)
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
    no_link = NO_LINK_MARKER in (captured or "")
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
    # 判定「已在库」用；老客户端不传就只按歌名判（判定不出来一律当没有）
    artist: Optional[str] = None
    # 已在库时仍要重下（比如原来那份是坏的、或想换更高音质）
    force: bool = False


class DownloadAlbumBody(BaseModel):
    album_mid: str
    quality: str = "flac"
    album_name: Optional[str] = None
    # 只下载选中的曲目；None = 整张（老客户端不传这个字段，行为完全不变）
    song_mids: Optional[list[str]] = None
    # 整张下载时强制全下（显式传了 song_mids 时本字段无意义：勾选本身就是强制）
    force: bool = False


class RetryCredentialBody(BaseModel):
    pass


class LibraryPathsBody(BaseModel):
    paths: list[str]


class LibraryRenameBody(BaseModel):
    path: str
    # 只写传了的字段，没传的一律不动
    title: Optional[str] = None
    artist: Optional[str] = None
    album: Optional[str] = None


# ─── 路由 ─────────────────────────────────────────────────

def _legacy_search(keyword: str, search_type: int, section: str, limit: int) -> list[dict]:
    """桌面搜索接口空结果时，使用仍受支持的兼容搜索接口。"""
    try:
        response = requests.get(
            LEGACY_SEARCH_URL,
            params={
                "w": keyword, "format": "json", "p": 1, "n": limit,
                "t": search_type, "cr": 1, "g_tk": 5381,
                "inCharset": "utf-8", "outCharset": "utf-8",
            },
            headers=SEARCH_HEADERS,
            timeout=15,
        )
        response.raise_for_status()
        section_data = response.json().get("data", {}).get(section, {})
        items = section_data.get("list", []) if isinstance(section_data, dict) else []
        return items if isinstance(items, list) else []
    except (requests.RequestException, ValueError, TypeError, AttributeError):
        log.exception("QQ 音乐兼容搜索接口失败: section=%s", section)
        return []


def _search_songs(keyword: str, auth: dict, limit: int) -> list[dict]:
    songs = qmc2.search_songs(keyword, auth["cookie"], str(auth["uin"]), limit=limit) or []
    if songs:
        return songs

    results = []
    for item in _legacy_search(keyword, 0, "song", limit):
        song_mid = item.get("songmid") or item.get("mid") or ""
        if not song_mid:
            continue
        singers = item.get("singer", [])
        singer = "/".join(
            str(value.get("name", "")) for value in singers
            if isinstance(value, dict) and value.get("name")
        ) if isinstance(singers, list) else str(singers or "")
        sizes = {
            "size_hires": item.get("sizehires") or 0,
            "size_flac": item.get("sizeflac") or 0,
            "size_320": item.get("size320") or 0,
            "size_128": item.get("size128") or 0,
        }
        results.append({
            "title": str(item.get("songname") or item.get("name") or ""),
            "singer": singer,
            "album": str(item.get("albumname") or item.get("album") or ""),
            "song_mid": str(song_mid),
            "media_mid": str(item.get("strMediaMid") or item.get("media_mid") or ""),
            **sizes,
            "available_qualities": [
                quality for quality, key in (
                    ("hires", "size_hires"), ("flac", "size_flac"),
                    ("320", "size_320"), ("128", "size_128"),
                ) if sizes[key]
            ],
        })
    return results


def _search_albums(keyword: str, auth: dict, limit: int) -> list[dict]:
    albums = qmc2.search_albums(keyword, auth["cookie"], str(auth["uin"]), limit=limit) or []
    if albums:
        return albums

    results = []
    for item in _legacy_search(keyword, 8, "album", limit):
        album_mid = item.get("albumMID") or item.get("albummid") or item.get("mid") or ""
        if not album_mid:
            continue
        results.append({
            "album_mid": str(album_mid),
            "album_id": item.get("albumID") or item.get("albumid") or 0,
            "album_name": str(item.get("albumName") or item.get("albumname") or ""),
            "singer": str(item.get("singerName") or item.get("singername") or ""),
            "public_time": str(item.get("publicTime") or item.get("publictime") or ""),
            "song_count": item.get("song_count") or item.get("songnum") or 0,
        })
    return results

@app.get("/api/health")
def health() -> dict:
    return {
        "ok": True,
        "ffmpeg_ready": _service_state["ffmpeg_ready"],
        "ffmpeg_error": _service_state["ffmpeg_error"],
        "credential_expired": _service_state["credential_expired"],
        "library_root": str(LIBRARY_ROOT),
        "library": {
            "root": str(LIBRARY_ROOT),
            # ready=False = 还在扫第一次，此时判定一律按「没有」，界面显示「扫描中…」
            "ready": _library_index.ready,
            "count": _library_index.count,
        },
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
    songs = _search_songs(q, auth, limit)
    downloading = _downloading_mids()
    for s in songs:
        s["available_qualities"] = [
            qz for qz, sz in (
                ("hires", s.get("size_hires") or 0),
                ("flac", s.get("size_flac") or 0),
                ("320", s.get("size_320") or 0),
                ("128", s.get("size_128") or 0),
            ) if sz
        ]
        s["in_library"] = _library_has(s.get("title"), s.get("singer"))
        s["downloading"] = s.get("song_mid") in downloading
    return {"songs": songs}


@app.get("/api/album/search")
def album_search(q: str, limit: int = 20) -> dict:
    q = q.strip()
    if not q:
        return {"albums": []}
    limit = max(1, min(limit, 50))
    auth = _auth_or_503()
    albums = _search_albums(q, auth, limit)
    return {"albums": albums}


@app.get("/api/album/{album_mid}/songs")
def album_songs(album_mid: str) -> dict:
    if not re.fullmatch(r"00[0-9A-Za-z]{8,16}", album_mid or ""):
        raise HTTPException(400, "无效的 album_mid")
    auth = _auth_or_503()
    songs = _album_song_details(album_mid, auth["cookie"], str(auth["uin"]))
    downloading = _downloading_mids()
    for s in songs:
        s["in_library"] = _library_has(s.get("name"), s.get("singer"))
        s["downloading"] = s.get("song_mid") in downloading
    # song_mids 一并回传：只需要 mid 的调用方（含老客户端）不用改。
    return {"songs": songs, "song_mids": [s["song_mid"] for s in songs]}


def _album_song_details(album_mid: str, cookie: str, uin: str) -> list[dict]:
    """专辑曲目（mid + 歌名 + 歌手）。

    qmc2.get_album_songs 只返回 mid 不带歌名，任务列表会显示成内部 ID；
    E:\music 不许改，所以这里复用同一底层接口把歌名一起取回来
    （请求次数与 get_album_songs 相同，只是多读字段）。
    歌手一并取回是为了「已有」判定能分辨同名不同人——接口没给就退回只按歌名判。
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
                    "singer": _join_singers(info.get("singer")),
                })
        start += len(song_list)
        total = data.get("totalNum", 0)
        if (total and start >= total) or len(song_list) < 100:
            break
    return details


def _join_singers(singers) -> str:
    """把接口回的歌手数组拼成 `周杰伦/费玉清` 这样——与搜索接口的写法一致。"""
    if not isinstance(singers, list):
        return ""
    return "/".join(
        str(entry.get("name") or entry.get("title") or "")
        for entry in singers
        if isinstance(entry, dict) and (entry.get("name") or entry.get("title"))
    )


def _auth_or_503() -> dict:
    if _service_state["credential_expired"]:
        raise HTTPException(503, "凭证已过期，请打开 QQ 音乐客户端并登录，然后在 App 里点「重试提取凭证」")
    try:
        return get_auth()
    except CredentialError as e:
        raise HTTPException(503, f"凭证缺失: {e}")


@app.post("/api/download")
def download(body: DownloadBody) -> dict:
    # 已在库且没要求重下 ⇒ 不入队。响应形状与正常入队**完全一致**（job_id 仍在，
    # 只是空串），只多一个 skipped 供新客户端识别——老客户端只认 job_id，
    # 字段少了会当成「下载服务返回了无法识别的数据」。
    if not body.force and _library_has(body.title, body.artist):
        return {"job_id": "", "skipped": True}
    job_id = _enqueue(body.song_mid, body.quality, title=body.title)
    return {"job_id": job_id, "skipped": False}


@app.post("/api/download/album")
def download_album(body: DownloadAlbumBody) -> dict:
    if not re.fullmatch(r"00[0-9A-Za-z]{8,16}", body.album_mid or ""):
        raise HTTPException(400, "无效的 album_mid")
    if body.quality not in QUALITIES:
        raise HTTPException(400, f"quality 必须是 {QUALITIES} 之一")
    # 选曲（App 的「选部分曲目下载」）：None = 整张
    wanted = None
    if body.song_mids is not None:
        wanted = {mid for mid in body.song_mids if mid}
        if not wanted:
            raise HTTPException(400, "没有选择任何曲目")
    auth = _auth_or_503()
    songs = _album_song_details(body.album_mid, auth["cookie"], str(auth["uin"]))
    if not songs:
        raise HTTPException(504, "未取到专辑曲目（网络或凭证问题）")
    if wanted is not None:
        # 按专辑内顺序过滤：既保证入队顺序自然，也顺手挡掉不属于该专辑的 mid
        #（选择是客户端传来的，不能当成可信输入直接入队）。
        songs = [s for s in songs if s["song_mid"] in wanted]
        if not songs:
            raise HTTPException(400, "所选曲目不在该专辑内（曲目列表可能已变化，请重新选择）")
    skipped_count = 0
    if wanted is None and not body.force:
        # 整张下载：库里已有的自动跳过，只下缺的。
        # 显式选了曲目则一律不跳——用户已经一首首挑过了，那是明确意图，
        # 勾选本身就是强制（选曲弹窗「已在库的能勾、勾了照下」就靠这条）。
        keep = []
        for s in songs:
            if _library_has(s.get("name"), s.get("singer")):
                skipped_count += 1
            else:
                keep.append(s)
        songs = keep  # 全部都在库时 count=0：这是正常结果，不是错误
    job_ids = [_enqueue(s["song_mid"], body.quality, title=s["name"] or None) for s in songs]
    return {
        "job_ids": job_ids,
        "count": len(job_ids),
        "skipped_count": skipped_count,
        "album_mid": body.album_mid,
    }


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


# ─── 曲库文件管理（危险接口，全部要密钥） ──────────────────
#
# 下面五个是**全服务仅有的会改动用户文件的路由**，所以每一个都挂了 require_key。
# 上面的搜索、下载、任务查询一律不挂——密钥对不上时那些必须照常能用。

# **这是全服务唯一会动用户数据的入口**。构造函数不碰磁盘。
_library_files = LibraryFiles(LIBRARY_ROOT)


def _refresh_library_index() -> None:
    """删/改完立刻重扫索引。

    索引原本的设计假设是「曲库只增不改」（library_index.py:37）。不刷新的话，
    删完一首想重下同一首，会被自己的去重挡下来——「我想下，它不让」。
    refresh() 按 mtime+size 跳过没变的文件，删/改之后只重读动过的那一个。

    单独兜异常：索引是贴心助手，它炸了不能让已经成功的文件操作报失败。
    """
    try:
        _library_index.refresh()
    except Exception:
        log.exception("文件操作后刷新曲库索引失败")


@app.post("/api/library/delete", dependencies=[Depends(require_key)])
def library_delete(body: LibraryPathsBody) -> dict:
    """删除 = 挪进回收站（ADR-0002），不是真删。"""
    result = _library_files.delete(body.paths)
    if result["deleted"]:
        _refresh_library_index()
    return result


@app.post("/api/library/rename", dependencies=[Depends(require_key)])
def library_rename(body: LibraryRenameBody) -> dict:
    """改名 = 写文件**内嵌标签**，不是改 Jellyfin 元数据（ADR-0001）。"""
    try:
        path = _library_files.rename(body.path, title=body.title,
                                     artist=body.artist, album=body.album)
    except LibraryFilesError as e:
        raise HTTPException(400, str(e))
    _refresh_library_index()
    return {"ok": True, "path": path}


@app.get("/api/library/recycle", dependencies=[Depends(require_key)])
def library_recycle() -> dict:
    files = _library_files.list_recycle()
    return {"files": files, "total_size": sum(f["size"] for f in files)}


@app.post("/api/library/recycle/restore", dependencies=[Depends(require_key)])
def library_restore(body: LibraryPathsBody) -> dict:
    result = _library_files.restore(body.paths)
    if result["restored"]:
        _refresh_library_index()
    return result


@app.post("/api/library/recycle/purge", dependencies=[Depends(require_key)])
def library_purge() -> dict:
    """**唯一不可逆的操作**，App 那边要二次确认。

    不刷索引：清空的只是回收站，曲库那边一个文件都没动。
    """
    return {"purged": _library_files.purge()}


def _start_index_scan() -> None:
    """先扫一遍曲库（就绪前的查询一律按「没有」），再起周期重扫。

    必须开独立线程：冷扫 596 个文件要十几秒，堵在 startup 里会让服务
    十几秒不接请求——而索引只是贴心助手，没资格让下载功能干等。
    """
    try:
        _library_index.refresh()
        log.info("曲库索引就绪: %s 首", _library_index.count)
    except Exception:
        log.exception("首次扫描曲库失败: %s", LIBRARY_ROOT)
    _library_index.start_watcher()


@app.on_event("startup")
def _startup() -> None:
    # 用户要从这里抄到 App 里，所以密钥得显眼地打出来
    log.info("文件操作密钥: %s        （存在 %s，App 设置页填这个）", _file_ops_key, KEY_FILE)
    threading.Thread(target=_worker, name="download-worker", daemon=True).start()
    threading.Thread(target=_start_index_scan, name="library-index-startup", daemon=True).start()


if __name__ == "__main__":
    import uvicorn
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    print(f"曲库目录: {LIBRARY_ROOT}")
    print("服务地址: http://0.0.0.0:8900  （手机访问 http://<电脑IP>:8900）")
    uvicorn.run(app, host="0.0.0.0", port=8900)
