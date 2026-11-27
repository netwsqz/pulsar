r"""
library_index.py — 曲库索引与「这首歌是不是已经有了」的判定
=============================================================

维护伴生服务共用的曲库索引：为 QQ 音乐导入去重，也为电脑曲库提供曲目清单。

判定钥匙优先取音频标签（TITLE/ARTIST），标签缺失才回退解析文件名——
下载器会把文件名里的 `<>:"/\\|?*` 清洗成 `_`，是有损的，换不回来。

**只读**：本模块绝不写入、移动、删除曲库里的任何文件。

独立于 qqmusic_service 是为了不把 E:\music 那套依赖（qmc2/frida）拖进测试。
"""

from __future__ import annotations

import argparse
import contextlib
import logging
import os
import re
import stat
import sys
import threading
import time
from dataclasses import asdict, dataclass
from hashlib import sha256
from pathlib import Path
from typing import Callable, Optional

from mutagen import File as MutagenFile
from mutagen.id3 import ID3
from mutagen.mp4 import MP4Tags

from library_artwork import embedded_artwork

log = logging.getLogger("library_index")

# 曲库里的音频扩展名；其余是 Jellyfin 生成的 .nfo 等，扫库必须过滤掉
AUDIO_EXTENSIONS = frozenset(
    {".flac", ".mp3", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".ape", ".wma"}
)

# 后台重扫周期。曲库只增不改，这是「手动拷进来的歌多久能被认出来」的上限
SCAN_INTERVAL_SECONDS = 180.0

# Jellyfin 音乐库目录（实时监控已开启，落盘即入库）
DEFAULT_LIBRARY_ROOT = Path("E:/file/Te_Music")

# 全角 ASCII（U+FF01–U+FF5E）与表意空格 → 半角
_FULLWIDTH_TO_ASCII = {code: code - 0xFEE0 for code in range(0xFF01, 0xFF5F)}
_FULLWIDTH_TO_ASCII[0x3000] = 0x20

# 弯引号、省略号等等价标点 → ASCII 写法（肉眼看着本来就是同一个字）
_PUNCT_EQUIVALENTS = {
    0x2018: "'", 0x2019: "'", 0x201A: "'", 0x201B: "'",
    0x201C: '"', 0x201D: '"', 0x201E: '"', 0x201F: '"',
    0x2026: "...",
    0x00A0: " ",
}


def normalize_text(value: str) -> str:
    """把歌名/歌手归一化成可比较的形式。

    只做「肉眼看着本来就是同一个字」的等价替换：全角半角、弯直引号、
    省略号写法、大小写、多余空格。

    **括号及其内容一律保留**——括号里的 Live / Remix / Instrumental /
    翻译恰恰是区分版本的关键，丢掉会让《晴天》和《晴天 (Live)》变成同一首。
    """
    if not value:
        return ""
    text = value.translate(_FULLWIDTH_TO_ASCII)
    text = text.translate(_PUNCT_EQUIVALENTS)
    return " ".join(text.casefold().split())


# 多歌手串联用的分隔符（`Lady Gaga/Bruno Mars`、`Bruno Mars & Silk Sonic`、
# 中文的 `、`、日文的 `・`）
_ARTIST_SEPARATORS = re.compile(r"[/&,、;；·・]")


def _artist_segments(artist: str) -> set[str]:
    """把歌手串拆成一个个歌手名，归一化后去空。"""
    if not artist:
        return set()
    return {segment for segment in map(normalize_text, _ARTIST_SEPARATORS.split(artist)) if segment}


def same_song(title_a: str, artist_a: str, title_b: str, artist_b: str) -> bool:
    """两首歌是不是同一首。

    歌名归一化后必须**完全相等**；歌手按**分段**判——一边的歌手全是另一边的
    子集就算对上（`Lady Gaga` ⊂ `Lady Gaga/Bruno Mars`，是写法差异，不是不同的人）。

    用分段而不是裸子串，是为了不让 `Tia` 因为碰巧是 `Tia Ray` 的子串而误判。
    任一边缺歌手信息时只按歌名判。
    """
    title_na, title_nb = normalize_text(title_a), normalize_text(title_b)
    if not title_na or title_na != title_nb:
        return False
    segments_a, segments_b = _artist_segments(artist_a), _artist_segments(artist_b)
    if not segments_a or not segments_b:
        return True
    return segments_a <= segments_b or segments_b <= segments_a


# 文件名开头的 track 号 / disc 号前缀：`11 `（落盘格式）、`2-11 `（多碟）
_TRACK_PREFIX_RE = re.compile(r"^(?:\d{1,2}-\d{1,3}|\d{1,3})[ .\-]+")
# QQ 客户端原始命名的音质后缀残留
_QUALITY_SUFFIX_RE = re.compile(r"_(?:hires|H)$")


def _tag_value(tags: dict, name: str) -> str:
    """按名字取标签值，大小写不敏感；多值取第一个。"""
    lowered = str(name).lower()
    for key, value in (tags or {}).items():
        if str(key).lower() != lowered:
            continue
        if isinstance(value, (list, tuple)):
            value = value[0] if value else ""
        return str(value or "").strip()
    return ""


def identity_from_file(filename: str, tags: dict | None) -> tuple[str, str]:
    """从文件名与音频标签得出这首歌的「(歌名, 歌手)」。

    标签优先。标签里没有歌名时才回退解析文件名：
    去掉开头的 track 号 / disc 号前缀、去掉 `_hires` / `_H` 音质后缀。

    回退时**带 track 号的文件名不做 `歌手 - 歌名` 拆分**——只有不带 track 号的
    外来命名（`keshi - beside you.flac`）才按 ` - ` 拆，否则歌名里的 ` - `
    会被误切成歌手。拆出来的歌手是被清洗过的形态，不猜着还原。
    """
    tags = tags or {}
    title = _tag_value(tags, "title")
    artist = _tag_value(tags, "artist") or _tag_value(tags, "albumartist")
    if title:
        return (title, artist)

    stem = _QUALITY_SUFFIX_RE.sub("", Path(filename).stem)
    numbered = _TRACK_PREFIX_RE.match(stem)
    if numbered:
        return (stem[numbered.end():].strip(), artist)
    if " - " in stem:
        name_part, _, title_part = stem.partition(" - ")
        return (title_part.strip(), artist or name_part.strip())
    return (stem.strip(), artist)


def read_tags(path: Path) -> dict:
    """读音频标签；读不出就返回空字典（交给文件名回退）。

    `easy=True` 是必须的：裸 mutagen 对 MP3 走 ID3 帧名（TIT2/TPE1），
    按 "title" 取会拿到 None。
    """
    try:
        audio = MutagenFile(path, easy=True)
    except Exception:  # 损坏文件、权限、非音频……一律当「读不出」
        return {}
    if audio is None or not audio.tags:
        return {}
    try:
        return {str(key): value for key, value in audio.tags.items()}
    except Exception:
        return {}


def _read_catalog_tags(path: Path) -> dict:
    """一次读出清单用的文字标签和有无封面；不把封面字节常驻在索引里。"""
    try:
        audio = MutagenFile(path)
    except Exception:
        return {}
    if audio is None:
        return {}
    tags = audio.tags
    if isinstance(tags, ID3):
        fields = {name: tags.get(frame) for name, frame in zip(
            ("title", "artist", "album", "albumartist"), ("TIT2", "TPE1", "TALB", "TPE2"),
        )}
        result = {name: value.text for name, value in fields.items() if value is not None}
    elif isinstance(tags, MP4Tags):
        result = {name: tags.get(atom) for name, atom in zip(
            ("title", "artist", "album", "albumartist"), ("\xa9nam", "\xa9ART", "\xa9alb", "aART"),
        )}
    else:
        result = dict(tags.items()) if tags is not None else {}
    try:
        result["has_artwork"] = embedded_artwork(audio) is not None
    except Exception:  # 坏封面不影响歌名、艺术家或专辑。
        result["has_artwork"] = False
    return result


def _unlinked_stat(path: Path) -> os.stat_result | None:
    try:
        info = path.lstat()
    except OSError:
        return None
    # Windows 目录联接不是 is_symlink()，但同样会把曲库路径重定向到别处。
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
        return None
    return info


def _plain_directory(path: Path) -> bool:
    info = _unlinked_stat(path)
    return info is not None and stat.S_ISDIR(info.st_mode)


def _plain_directory_chain(path: Path) -> bool:
    # 检查到卷根，避免曲库路径本身的父目录是联接而整库被重定向。
    return all(_plain_directory(parent) for parent in reversed(path.parents)) and _plain_directory(path)


def _audio_stat(path: Path) -> os.stat_result | None:
    if path.suffix.lower() not in AUDIO_EXTENSIONS:
        return None
    info = _unlinked_stat(path)
    return info if info is not None and stat.S_ISREG(info.st_mode) else None


def _normalized_path(raw: str) -> str | None:
    # 用户给的路径只作为索引查找键，绝不直接用来打开文件。
    if not raw or "\x00" in raw or not Path(raw).is_absolute() or ".." in Path(raw).parts:
        return None
    return os.path.normcase(os.path.normpath(raw))


def _file_stamp(info: os.stat_result) -> tuple[int, ...]:
    # Windows 的 lstat 与 fstat 对 ctime 可能给出不同含义；比较双方共有的字段。
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def same_audio_file(expected: os.stat_result, opened: os.stat_result) -> bool:
    """比较索引查找与实际打开的文件，兼容 Windows lstat 与 fstat。"""
    return stat.S_ISREG(opened.st_mode) and _file_stamp(expected) == _file_stamp(opened)


def audio_revision(info: os.stat_result) -> str:
    # 仅用来发现文件变化，不能代替首次核对时的整份字节摘要。
    return sha256(repr((*_file_stamp(info), info.st_ctime_ns)).encode("ascii")).hexdigest()


@dataclass(frozen=True)
class LibraryTrack:
    id: str
    title: str
    artist: str
    album: str
    has_artwork: bool = False


class LibraryIndex:
    """伴生服务共用的曲库索引：扫描一次，供 QQ 音乐导入去重和电脑曲库读取。

    只读——绝不写入、移动、删除曲库里的任何文件。

    索引没就绪（还没扫完）时 [contains] 一律返回 False：判定不出来就当
    「没有」，宁可漏判让用户多下一次，也不误判挡住用户。
    """

    def __init__(self, root: Path, read_tags: Callable[[Path], dict] = _read_catalog_tags):
        self._root = Path(root).absolute()
        self._read_tags = read_tags
        self._refresh_lock = threading.Lock()
        self._lock = threading.Lock()
        self._tracks: dict[Path, LibraryTrack] = {}
        self._paths_by_id: dict[str, Path] = {}
        self._stamps: dict[Path, tuple[int, int]] = {}
        self._by_title: dict[str, list[set[str]]] = {}
        # 手机给的旧文件路径只用来找候选，不证明内容一致。
        self._by_normcase: dict[str, Path] = {}
        self._ready = False
        self._readable_root = False

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._ready

    @property
    def catalog_ready(self) -> bool:
        """可供配对的索引必须至少读过一次曲库根目录；旧去重的 ready 语义不变。"""
        with self._lock:
            root_was_read = self._readable_root
        return root_was_read and _plain_directory_chain(self._root)

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._tracks)

    def entries(self) -> list[tuple[str, str]]:
        """索引里的全部 (歌名, 歌手)，按歌名排序。"""
        return [(track.title, track.artist) for track in self.tracks()]

    def tracks(self) -> list[LibraryTrack]:
        """电脑曲库清单；快照不含文件路径。"""
        with self._lock:
            tracks = list(self._tracks.values())
        return sorted(tracks, key=lambda track: (track.title.casefold(), track.artist.casefold()))

    def catalog(self) -> list[dict]:
        """附带当前文件版本；稳定曲目 ID 不变，替换文件不沿用已确认的副本。"""
        result = []
        for track in self.tracks():
            file = self.audio_file(track.id)
            if file is not None:
                _, info = file
                result.append({**asdict(track), "revision": audio_revision(info)})
        return result

    def recently_added(self) -> list[str]:
        """Windows 以文件创建时间排序，避免改内嵌标签把旧歌算作新添加。"""
        added = []
        for track in self.tracks():
            file = self.audio_file(track.id)
            if file is not None:
                _, info = file
                fallback = info.st_ctime_ns if os.name == "nt" else info.st_mtime_ns
                added.append((getattr(info, "st_birthtime_ns", fallback), track.id))
        return [track_id for _, track_id in sorted(added, reverse=True)]

    def audio_file(self, track_id: str) -> tuple[Path, os.stat_result] | None:
        """只按索引 ID 查找音频；即使文件在扫描后被替换，也重新检查曲库边界。"""
        with self._lock:
            path = self._paths_by_id.get(track_id)
        if path is None:
            return None
        info = self._checked_audio_stat(path)
        return (path, info) if info is not None else None

    @staticmethod
    def read_artwork(path: Path, info: os.stat_result) -> tuple[bytes, str] | None:
        """只读已核对的文件；读不到不影响音频或整份清单。"""
        try:
            with path.open("rb") as file:
                if not same_audio_file(info, os.fstat(file.fileno())):
                    return None
                artwork = embedded_artwork(MutagenFile(file))
                if not same_audio_file(info, os.fstat(file.fileno())):
                    return None
            return artwork
        except Exception:  # 标签损坏、格式不支持或读取失败都走占位图。
            return None

    def evidence(self, paths: list[str]) -> list[dict]:
        """按手机给的旧文件路径找索引里的同一份文件，返回大小与 SHA-256。

        **只读**：不打开索引外的文件，不写入、不移动曲库。路径只用来找候选——
        路径相同不代表字节相同（文件可能被替换），所以手机必须再用摘要比对。
        路径写法不同（大小写、分隔符）按系统规则归一化；相对路径一律不认。
        """
        results: list[dict] = []
        for raw in dict.fromkeys(paths):
            normalized = _normalized_path(raw)
            if normalized is None:
                continue
            with self._lock:
                path = self._by_normcase.get(normalized)
                track = self._tracks.get(path) if path is not None else None
            if path is None or track is None:
                continue
            info = self._checked_audio_stat(path)
            if info is None:
                continue
            digest = self._digest(path, info) if info.st_size > 0 else None
            if digest is None:
                continue
            results.append({
                **asdict(track), "path": raw, "size": info.st_size,
                "sha256": digest, "revision": audio_revision(info),
            })
        return results

    def _digest(self, path: Path, info: os.stat_result) -> str | None:
        """整首文件的内容摘要。读失败只影响这一条，不抛给接口。"""
        stamp = _file_stamp(info)
        try:
            with path.open("rb") as file:
                if _file_stamp(os.fstat(file.fileno())) != stamp:
                    return None
                digest = sha256()
                remaining = info.st_size
                while remaining:
                    chunk = file.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        return None
                    digest.update(chunk)
                    remaining -= len(chunk)
                if file.read(1) or _file_stamp(os.fstat(file.fileno())) != stamp:
                    return None
        except OSError:
            return None
        latest = self._checked_audio_stat(path)
        return digest.hexdigest() if latest is not None and _file_stamp(latest) == stamp else None

    def refresh(self) -> None:
        """重扫曲库。没变的文件不重读标签——读标签是这里唯一的重活。

        读标签在锁外做：冷扫 595 个文件要十几秒，不能把查询请求堵死。
        """
        # 周期重扫和文件操作触发的重扫共用一个扫描锁；查询仍只读上一次的快照。
        with self._refresh_lock:
            self._refresh()

    def _refresh(self) -> None:
        with self._lock:
            known = dict(self._stamps)
        snapshot = self._scan()
        current = snapshot if snapshot is not None else {}
        fresh = {
            path: self._read_track(path)
            for path, stamp in current.items()
            if known.get(path) != stamp
        }
        with self._lock:
            # 扫描期间 note_downloaded 可能已经并入新曲目。它读取标签更晚，
            # 保留它的快照（包括与本轮扫描碰巧看见了同一个路径的情况）。
            for path, stamp in self._stamps.items():
                if known.get(path) != stamp:
                    current[path] = stamp
                    fresh.pop(path, None)
            self._tracks.update(fresh)
            for path in self._tracks.keys() - current.keys():
                del self._tracks[path]
            self._stamps = current
            self._rebuild_locked()
            self._readable_root = snapshot is not None
            self._ready = True

    def note_downloaded(self, path: Path) -> None:
        """把刚下载完的文件并入索引，不必等下一次周期扫描。"""
        path = Path(path).absolute()
        info = self._checked_audio_stat(path)
        if info is None:
            return
        track = self._read_track(path)
        with self._lock:
            self._tracks[path] = track
            self._stamps[path] = (info.st_mtime_ns, info.st_size)
            self._rebuild_locked()

    def contains(self, title: str, artist: str) -> bool:
        """曲库里有没有这首歌。索引没就绪时一律 False（宁可漏判）。"""
        key = normalize_text(title)
        if not key:
            return False
        with self._lock:
            if not self._ready:
                return False
            candidates = self._by_title.get(key)
            if not candidates:
                return False
            query = _artist_segments(artist)
            if not query:
                return True
            return any(not stored or query <= stored or stored <= query for stored in candidates)

    def start_watcher(self, interval_seconds: float = SCAN_INTERVAL_SECONDS) -> None:
        """起后台线程定期重扫。周期到了先看目录快照，没变就不重读标签。"""

        def loop() -> None:
            while True:
                time.sleep(interval_seconds)
                try:
                    self.refresh()
                except Exception:  # 扫库失败不能拖垮整个服务
                    log.exception("曲库重扫失败: %s", self._root)

        threading.Thread(target=loop, name="library-index-watcher", daemon=True).start()

    # ─── 内部 ───

    def _read_track(self, path: Path) -> LibraryTrack:
        tags = self._read_tags(path)
        title, artist = identity_from_file(path.name, tags)
        # ID 只取曲库相对路径，不随内嵌标签、内容或进程重启改变。
        relative = path.relative_to(self._root).as_posix()
        track_id = sha256(relative.encode("utf-8", errors="surrogatepass")).hexdigest()
        return LibraryTrack(track_id, title, artist, _tag_value(tags, "album"), tags.get("has_artwork") is True)

    def _checked_audio_stat(self, path: Path) -> os.stat_result | None:
        if path == self._root or not path.is_relative_to(self._root):
            return None
        if ".." in path.parts or not _plain_directory_chain(path.parent):
            return None
        return _audio_stat(path)

    def _scan(self) -> dict[Path, tuple[int, int]] | None:
        """一遍扫描；遇到目录联接或符号链接不进入、不读标签。"""
        found: dict[Path, tuple[int, int]] = {}
        if not _plain_directory_chain(self._root):
            return None
        walked = False
        for directory, subdirs, files in os.walk(self._root, followlinks=False):
            walked = True
            parent = Path(directory)
            # Windows 的 os.walk(followlinks=False) 仍可能走进目录联接。
            subdirs[:] = [name for name in subdirs if _plain_directory(parent / name)]
            for name in files:
                path = parent / name
                info = _audio_stat(path)
                if info is not None:
                    found[path] = (info.st_mtime_ns, info.st_size)
        return found if walked else None

    def _rebuild_locked(self) -> None:
        """按归一化歌名建查表；歌手存成分段集合供包含判定。"""
        by_title: dict[str, list[set[str]]] = {}
        for track in self._tracks.values():
            key = normalize_text(track.title)
            if key:
                by_title.setdefault(key, []).append(_artist_segments(track.artist))
        self._paths_by_id = {track.id: path for path, track in self._tracks.items()}
        self._by_normcase = {
            os.path.normcase(os.path.abspath(path)): path for path in self._tracks
        }
        self._by_title = by_title


# ─── 命令行入口（拿真曲库验判定准不准） ────────────────────

def _format_entry(title: str, artist: str) -> str:
    return f"{title} — {artist}" if artist else title


def main(argv: Optional[list[str]] = None) -> int:
    """扫一遍真曲库，看看服务端认为你已经有哪些歌。

        python library_index.py                    摘要
        python library_index.py --dump             全部条目
        python library_index.py --query 晴天 周杰伦  查某一首在不在库里
    """
    parser = argparse.ArgumentParser(
        prog="library_index",
        description="曲库索引：看看服务端认为你已经有哪些歌",
    )
    parser.add_argument("--root", type=Path, default=DEFAULT_LIBRARY_ROOT,
                        help=f"曲库目录（默认 {DEFAULT_LIBRARY_ROOT}）")
    parser.add_argument("--dump", action="store_true", help="打印索引里的全部条目")
    parser.add_argument("--query", nargs="+", metavar="歌名 [歌手]",
                        help="查询某一首在不在库里；退出码 0 = 已有，1 = 没有")
    parser.add_argument("--sample", type=int, default=10,
                        help="摘要里打印几条样例（默认 10，0 = 不打印）")
    args = parser.parse_args(argv)

    # 控制台编码兜底：中文输出在重定向/奇怪代码页下不能把进程炸掉
    with contextlib.suppress(Exception):
        sys.stdout.reconfigure(errors="replace")

    index = LibraryIndex(args.root)
    started = time.monotonic()
    index.refresh()
    elapsed = time.monotonic() - started

    if args.query:
        title, artist = args.query[0], " ".join(args.query[1:])
        found = index.contains(title, artist)
        print(f"{'【已有】' if found else '【没有】'}{_format_entry(title, artist)}")
        return 0 if found else 1

    print(f"曲库目录: {args.root}")
    print(f"已索引  : {index.count} 首    耗时 {elapsed:.1f} 秒")
    if args.dump:
        for title, artist in index.entries():
            print(_format_entry(title, artist))
    elif args.sample > 0:
        print(f"\n样例（前 {args.sample} 条）:")
        for title, artist in index.entries()[: args.sample]:
            print(f"  {_format_entry(title, artist)}")
        print("\n（--dump 打印全部；--query 歌名 [歌手] 查某一首）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())



