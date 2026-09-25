"""
library_files.py — 曲库文件操作：删除（移回收站）、改名（写内嵌标签）、恢复、清空
================================================================================

给 App 用的一组文件操作。**这是整个服务里唯一会动用户数据的模块**，
所以护栏比功能多：

  1. 所有路径 resolve 之后必须落在曲库（或回收站）之内，否则拒绝
  2. 删除 = 挪进回收站，**保持原相对路径**——恢复就是原路挪回来，不用记账
  3. 只清「没有别的东西」的目录：只剩 `.nfo` 才清，还有任何别的文件就整个不动
  4. 恢复时目标位置已有同名文件就拒绝，**绝不覆盖**

独立于 qqmusic_service 是为了不把 E:\\music 那套依赖（qmc2/frida）拖进测试，
跟 library_index.py 同一个理由。跟它分工：**索引只读，这里专管写**。

启动:  见 qqmusic_service.py
依赖:   mutagen（服务环境已装，不新增依赖）
"""

from __future__ import annotations

import argparse
import contextlib
import logging
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Iterable, Optional

from mutagen import File as MutagenFile

from library_index import DEFAULT_LIBRARY_ROOT, _tag_value, read_tags

log = logging.getLogger("library_files")

# 回收站必须**在曲库外面**：放里面会被 Jellyfin 当曲库扫进去，
# 「删掉」的歌会原样出现在 App 里。（ADR-0002）
DEFAULT_RECYCLE_ROOT = Path("E:/file/Te_Music_deleted")

# 删歌时可以跟着一起清掉的旁挂文件——只有 Jellyfin 自己生成的 .nfo。
# 别的一律算「还有别的东西」，整个目录不动：宁可留空壳，也不能误删。
SIDECAR_EXTENSIONS = frozenset({".nfo"})


class LibraryFilesError(RuntimeError):
    """明确的拒绝：路径越界、目标已存在、写标签失败……"""


def _free_path(path: Path) -> Path:
    """目标已存在时加序号避开。**绝不覆盖回收站里已有的东西。**"""
    if not path.exists():
        return path
    for n in range(2, 1000):
        candidate = path.with_name(f"{path.stem} ({n}){path.suffix}")
        if not candidate.exists():
            return candidate
    raise LibraryFilesError(f"回收站里同名文件太多: {path.name}")


class LibraryFiles:
    """曲库文件的增删改。**所有入参路径都会被校验在范围内。**"""

    def __init__(self, library_root: Path = DEFAULT_LIBRARY_ROOT,
                 recycle_root: Path = DEFAULT_RECYCLE_ROOT):
        self._library = Path(library_root).resolve()
        self._recycle = Path(recycle_root).resolve()

    # ─── 删除 ─────────────────────────────────────────────

    def delete(self, paths: Iterable[str]) -> dict:
        """把文件挪进回收站。单个失败不挡其他——返回里分开列。"""
        deleted, failed = [], []
        for raw in paths:
            try:
                source = self._checked(raw)
                if not source.is_file():
                    raise LibraryFilesError(f"不是文件: {raw!r}")
                rel = source.relative_to(self._library)
                self._recycle_file(source, rel)
                deleted.append(rel.as_posix())
                self._prune(source.parent)
            except Exception as e:  # 一个坏路径不该拖垮整批
                failed.append({"path": raw, "error": f"{type(e).__name__}: {e}"})
        return {"deleted": deleted, "failed": failed}

    # ─── 改名 ─────────────────────────────────────────────

    def rename(self, path: str, title: Optional[str] = None,
               artist: Optional[str] = None, album: Optional[str] = None) -> str:
        """改文件**内嵌标签**（不是 Jellyfin 元数据，见 ADR-0001）。

        只写传了的字段，没传的一律不动。
        写完立刻读回来核对——静默失败最坑，用户会以为改完了。
        """
        target = self._checked(path)
        if not target.is_file():
            raise LibraryFilesError(f"文件不存在: {path!r}")
        fields = {key: str(value) for key, value in
                  (("title", title), ("artist", artist), ("album", album))
                  if value is not None}
        if not fields:
            raise LibraryFilesError("没有要修改的字段")

        # easy=True 是必须的：裸 mutagen 对 MP3 走 ID3 帧名（TIT2/TPE1），
        # 按 "title" 写会写错帧名。跟 library_index.read_tags 保持一致。
        audio = MutagenFile(str(target), easy=True)
        if audio is None or not hasattr(audio, "save"):
            raise LibraryFilesError(f"读不出音频标签（可能不是音频文件）: {target.name}")
        try:
            for key, value in fields.items():
                audio[key] = [value]
            audio.save()
        except Exception as e:
            raise LibraryFilesError(f"写标签失败: {type(e).__name__}: {e}")

        got = read_tags(target)
        for key, value in fields.items():
            actual = _tag_value(got, key)
            if actual != value:
                raise LibraryFilesError(
                    f"写标签后校验不通过：{key} 期望 {value!r}，实际读到 {actual!r}"
                )
        return target.relative_to(self._library).as_posix()

    # ─── 恢复 ─────────────────────────────────────────────

    def restore(self, rel_paths: Iterable[str]) -> dict:
        """从回收站挪回原处。目标位置已有同名文件时拒绝，不覆盖。"""
        restored, failed = [], []
        for raw in rel_paths:
            try:
                source = self._checked(raw, root=self._recycle)
                if not source.is_file():
                    raise LibraryFilesError(f"回收站里没有这个文件: {raw!r}")
                rel = source.relative_to(self._recycle)
                destination = self._library / rel
                if destination.exists():
                    raise LibraryFilesError(f"原位置已有同名文件，不覆盖: {rel.as_posix()}")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(source), str(destination))
                restored.append(rel.as_posix())
                # 文件挪走了，它在回收站里占的目录壳也得跟着消失，
                # 否则每来回一趟就积一个空目录。
                self._remove_empty_dirs(source.parent, self._recycle)
            except Exception as e:
                failed.append({"path": raw, "error": f"{type(e).__name__}: {e}"})
        return {"restored": restored, "failed": failed}

    # ─── 回收站 ───────────────────────────────────────────

    def list_recycle(self) -> list[dict]:
        """回收站里的文件，最近的排前面。"""
        if not self._recycle.is_dir():
            return []
        entries = []
        for path in self._recycle.rglob("*"):
            if not path.is_file():
                continue
            stat = path.stat()
            entries.append({
                "rel_path": path.relative_to(self._recycle).as_posix(),
                "size": stat.st_size,
                "deleted_at": stat.st_mtime,
            })
        return sorted(entries, key=lambda item: item["deleted_at"], reverse=True)

    def purge(self) -> int:
        """**真的删除**回收站里的东西（全功能里唯一不可逆的操作）。"""
        if not self._recycle.is_dir():
            return 0
        count = sum(1 for path in self._recycle.rglob("*") if path.is_file())
        shutil.rmtree(self._recycle)
        return count

    # ─── 内部 ─────────────────────────────────────────────

    def _checked(self, raw: str, root: Optional[Path] = None) -> Path:
        """把入参路径解析出来，确认它落在 root 之内，否则拒绝。

        先 `resolve()` 再比对父级链，`../` 和绝对路径注入都挡得住。
        """
        root = root if root is not None else self._library
        candidate = Path(raw)
        if not candidate.is_absolute():
            candidate = root / candidate
        resolved = candidate.resolve()
        if resolved != root and root not in resolved.parents:
            raise LibraryFilesError(f"路径越出范围: {raw!r}")
        return resolved

    def _recycle_file(self, source: Path, rel: Path) -> None:
        """挪一个文件进回收站，保持 rel 这个相对路径。"""
        destination = _free_path(self._recycle / rel)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source), str(destination))
        # shutil.move 保留原 mtime，那是文件的旧时间，不是删除时间；
        # 设置页要按「刚删的排前面」，所以这里改成现在。
        now = time.time()
        os.utime(destination, (now, now))

    def _remove_empty_dirs(self, directory: Path, root: Path) -> None:
        """从 directory 一路往上删空目录，到 root 为止。

        `rmdir()` 遇到非空目录会抛 OSError——那正是我们要的刹车，
        不用自己判断「空不空」。
        """
        current = directory
        while current != root and root in current.parents:
            try:
                current.rmdir()
            except OSError:
                break
            current = current.parent

    def _prune(self, directory: Path) -> None:
        """从 directory 一路往上清，把只剩旁挂文件的空目录也挪进回收站。

        只要目录里还有**任何一个**不是 `.nfo` 的文件、或者还有子目录，立刻停手。
        宁可留个空壳，也不能误删别的东西。
        """
        current = directory
        while current != self._library and self._library in current.parents:
            if not current.is_dir():
                break
            entries = list(current.iterdir())
            if any(entry.is_dir() for entry in entries):
                break
            if any(entry.suffix.lower() not in SIDECAR_EXTENSIONS for entry in entries):
                break
            for entry in entries:
                self._recycle_file(entry, entry.relative_to(self._library))
            current.rmdir()
            current = current.parent


# ─── 命令行入口（拿真曲库验一遍，比单测更有说服力） ──────────

def _selftest(root: Path, recycle: Path) -> int:
    """在**真曲库**里造一个临时文件，走完整流程，最后自己收拾干净。

    **故意不测 purge**——那会把用户回收站里已有的东西一起真删掉。
    purge 的行为由单元测试（临时目录）覆盖。
    """
    files = LibraryFiles(root, recycle)
    probe_dir = root / "_pulsar_selftest"
    probe = probe_dir / "probe.txt"
    rel = "_pulsar_selftest/probe.txt"

    checks = []

    def check(label: str, ok: bool) -> None:
        checks.append(ok)
        print(f"  [{'OK  ' if ok else '失败'}] {label}")

    try:
        probe_dir.mkdir(parents=True, exist_ok=True)
        probe.write_text("pulsar selftest")

        result = files.delete([str(probe)])
        check("删除：文件离开了曲库", not probe.exists())
        check("删除：回收站里保持原相对路径",
              result["deleted"] == [rel] and (recycle / rel).is_file())
        check("删除：清掉了只剩旁挂文件的目录", not probe_dir.exists())

        check("回收站：列得出这一条",
              any(entry["rel_path"] == rel for entry in files.list_recycle()))

        files.restore([rel])
        check("恢复：文件回到原处", probe.is_file())

        files.delete([str(probe)])
        files.restore([rel])
        leftovers = ([entry for entry in recycle.rglob("*") if "_pulsar_selftest" in entry.parts]
                     if recycle.is_dir() else [])
        check("来回一趟没留下垃圾（含回收站里的空目录壳）",
              probe.is_file() and not leftovers)
    finally:
        # 收拾自己的痕迹。只动我们自己造的目录，不碰回收站。
        if probe.exists():
            probe.unlink()
        if probe_dir.exists():
            for leftover in probe_dir.iterdir():
                leftover.unlink()
            probe_dir.rmdir()

    ok = all(checks)
    print("\n全部通过。" if ok else "\n有失败项——先别用，去查上面的输出。")
    return 0 if ok else 1


def main(argv: Optional[list[str]] = None) -> int:
    """在真曲库上验文件操作。

        python library_files.py --selftest    造个临时文件走完整流程（推荐）
        python library_files.py --list        看看回收站里现在有什么
    """
    parser = argparse.ArgumentParser(
        prog="library_files",
        description="曲库文件操作：删除 / 改名 / 恢复 / 回收站",
    )
    parser.add_argument("--root", type=Path, default=DEFAULT_LIBRARY_ROOT,
                        help=f"曲库目录（默认 {DEFAULT_LIBRARY_ROOT}）")
    parser.add_argument("--recycle", type=Path, default=DEFAULT_RECYCLE_ROOT,
                        help=f"回收站目录（默认 {DEFAULT_RECYCLE_ROOT}）")
    parser.add_argument("--selftest", action="store_true",
                        help="造个临时文件走完整流程（删→列出→恢复），跑完自动清理")
    parser.add_argument("--list", action="store_true", help="列出回收站内容")
    args = parser.parse_args(argv)

    with contextlib.suppress(Exception):
        sys.stdout.reconfigure(errors="replace")

    if args.selftest:
        print(f"曲库  : {args.root}")
        print(f"回收站: {args.recycle}\n")
        return _selftest(args.root, args.recycle)

    files = LibraryFiles(args.root, args.recycle)
    entries = files.list_recycle()
    if not entries:
        print("回收站是空的。")
        return 0
    total = sum(entry["size"] for entry in entries)
    print(f"回收站: {args.recycle}")
    print(f"{len(entries)} 个文件 · {total / 1024 / 1024:.1f} MB\n")
    for entry in entries:
        print(f"  {entry['size'] / 1024 / 1024:7.1f} MB  {entry['rel_path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
