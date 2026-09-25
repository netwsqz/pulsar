"""
library_index.py — 曲库索引与「这首歌是不是已经有了」的判定
=============================================================

扫描 Jellyfin 音乐库目录，回答「曲库里有没有这首歌」。
供 qqmusic_service 在搜索/专辑接口里打标记、在下载接口里挡重复。

判定钥匙优先取音频标签（TITLE/ARTIST），标签缺失才回退解析文件名——
下载器会把文件名里的 `<>:"/\\|?*` 清洗成 `_`，是有损的，换不回来。

**只读**：本模块绝不写入、移动、删除曲库里的任何文件。

独立于 qqmusic_service 是为了不把 E:\music 那套依赖（qmc2/frida）拖进测试。
"""

from __future__ import annotations

import argparse
import contextlib
import logging
import re
import sys
import threading
import time
from pathlib import Path
from typing import Callable, Optional

from mutagen import File as MutagenFile

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


class LibraryIndex:
    """曲库索引：扫一遍曲库，之后回答「这首歌是不是已经有了」。

    只读——绝不写入、移动、删除曲库里的任何文件。

    索引没就绪（还没扫完）时 [contains] 一律返回 False：判定不出来就当
    「没有」，宁可漏判让用户多下一次，也不误判挡住用户。
    """

    def __init__(self, root: Path, read_tags: Callable[[Path], dict] = read_tags):
        self._root = Path(root)
        self._read_tags = read_tags
        self._lock = threading.Lock()
        self._identities: dict[Path, tuple[str, str]] = {}
        self._stamps: dict[Path, tuple[float, int]] = {}
        self._by_title: dict[str, list[set[str]]] = {}
        self._ready = False

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._ready

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._identities)

    def entries(self) -> list[tuple[str, str]]:
        """索引里的全部 (歌名, 歌手)，按歌名排序。"""
        with self._lock:
            return sorted(
                self._identities.values(),
                key=lambda item: (item[0].casefold(), item[1].casefold()),
            )

    def refresh(self) -> None:
        """重扫曲库。没变的文件不重读标签——读标签是这里唯一的重活。

        读标签在锁外做：冷扫 595 个文件要十几秒，不能把查询请求堵死。
        """
        current = self._scan()
        with self._lock:
            known = dict(self._stamps)
        fresh = {
            path: identity_from_file(path.name, self._read_tags(path))
            for path, stamp in current.items()
            if known.get(path) != stamp
        }
        with self._lock:
            self._identities.update(fresh)
            stamps = dict(current)
            for path in list(self._identities):
                if path in stamps:
                    continue
                # 不在快照里 ≠ 已经没了：快照是读标签**之前**取的，这期间
                # worker 可能刚好下载落盘并 note_downloaded 并了进来。
                # 只有文件真没了才清掉（顺带把它补进 stamps，省一次重读标签）。
                stamp = self._stamp(path)
                if stamp is None:
                    del self._identities[path]
                else:
                    stamps[path] = stamp
            self._stamps = stamps
            self._rebuild_locked()
            self._ready = True

    def note_downloaded(self, path: Path) -> None:
        """把刚下载完的文件并入索引，不必等下一次周期扫描。"""
        path = Path(path)
        stamp = self._stamp(path)
        if stamp is None:
            return
        identity = identity_from_file(path.name, self._read_tags(path))
        with self._lock:
            self._identities[path] = identity
            self._stamps[path] = stamp
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

    def _scan(self) -> dict[Path, tuple[float, int]]:
        """列出曲库里的音频文件及其 (mtime, size) 快照。"""
        found: dict[Path, tuple[float, int]] = {}
        try:
            for path in self._root.rglob("*"):
                if path.suffix.lower() not in AUDIO_EXTENSIONS or not path.is_file():
                    continue
                stamp = self._stamp(path)
                if stamp is not None:
                    found[path] = stamp
        except OSError:
            log.exception("扫描曲库失败: %s", self._root)
        return found

    @staticmethod
    def _stamp(path: Path) -> Optional[tuple[float, int]]:
        try:
            stat = path.stat()
        except OSError:
            return None
        return (stat.st_mtime, stat.st_size)

    def _rebuild_locked(self) -> None:
        """按归一化歌名建查表；歌手存成分段集合供包含判定。"""
        by_title: dict[str, list[set[str]]] = {}
        for title, artist in self._identities.values():
            key = normalize_text(title)
            if key:
                by_title.setdefault(key, []).append(_artist_segments(artist))
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



