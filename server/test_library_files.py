"""曲库文件操作的测试。

先例见 test_library_index.py：unittest + tmp_path 式假目录，只测外部行为。
这一层是**唯一碰用户数据的地方**，所以覆盖重点是「不该动的东西有没有被动」——
路径越界、目录里还有别的文件、恢复会不会覆盖。

真文件用 `_write_flac()` 现造一个最小合法 FLAC（magic + STREAMINFO），
mutagen 认它，所以改标签能真跑，不用拿真曲库赌。
"""

import tempfile
import unittest
from pathlib import Path

from library_files import LibraryFiles, LibraryFilesError
from library_index import read_tags


def minimal_flac(rate: int = 44100, channels: int = 2, bits: int = 16) -> bytes:
    """一个能被 mutagen 解析的最小 FLAC（没有音频帧，但标签读写齐全）。"""
    si = bytearray(34)
    si[0:2] = (4096).to_bytes(2, "big")   # min blocksize
    si[2:4] = (4096).to_bytes(2, "big")   # max blocksize
    si[10] = (rate >> 12) & 0xFF
    si[11] = (rate >> 4) & 0xFF
    si[12] = ((rate & 0xF) << 4) | ((channels - 1) << 1) | (((bits - 1) >> 4) & 1)
    si[13] = ((bits - 1) & 0xF) << 4
    return b"fLaC" + bytes([0x80]) + (34).to_bytes(3, "big") + bytes(si)


class LibraryFilesTestCase(unittest.TestCase):
    """公共脚手架：每个测试一套独立的假曲库 + 假回收站。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.library = root / "Te_Music"
        self.recycle = root / "Te_Music_deleted"
        self.library.mkdir()
        self.files = LibraryFiles(self.library, self.recycle)

    def tearDown(self):
        self._tmp.cleanup()

    def song(self, rel: str, **tags: str) -> Path:
        path = self.library / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(minimal_flac())
        if tags:
            self.files.rename(str(path), **tags)
        return path


# ─── 路径护栏：任何操作都不许越出曲库/回收站 ───────────────


class PathGuardTest(LibraryFilesTestCase):
    """批量接口（delete/restore）把坏路径收进 failed 返回，不往上抛——
    所以这里既要断言被拒，也要断言文件**原地没动**，还要断言拒的理由是「越界」
    而不是碰巧因为别的原因（比如文件不存在）失败。
    """

    def outsider(self) -> Path:
        path = self.library.parent / "别人的文件.flac"
        path.write_bytes(b"x")
        return path

    def test_rejects_path_outside_library(self):
        outsider = self.outsider()

        result = self.files.delete([str(outsider)])

        self.assertEqual([], result["deleted"])
        self.assertEqual(1, len(result["failed"]))
        self.assertIn("越出范围", result["failed"][0]["error"])
        self.assertTrue(outsider.exists(), "越界的文件必须原地不动")

    def test_rejects_parent_traversal(self):
        outsider = self.outsider()

        result = self.files.delete([str(self.library / ".." / "别人的文件.flac")])

        self.assertEqual([], result["deleted"])
        self.assertIn("越出范围", result["failed"][0]["error"])
        self.assertTrue(outsider.exists())

    def test_rejects_rename_outside_library(self):
        """rename 是单条的，直接抛——不会静默失败。"""
        outsider = self.outsider()

        with self.assertRaises(LibraryFilesError):
            self.files.rename(str(outsider), title="改不了")

        self.assertEqual(b"x", outsider.read_bytes(), "越界的文件必须原地不动")

    def test_rejects_restore_traversal(self):
        outsider = self.outsider()

        result = self.files.restore(["../别人的文件.flac"])

        self.assertEqual([], result["restored"])
        self.assertIn("越出范围", result["failed"][0]["error"])
        self.assertTrue(outsider.exists())


# ─── 删除 ────────────────────────────────────────────────


class DeleteTest(LibraryFilesTestCase):
    def test_moves_file_to_recycle_keeping_relative_path(self):
        self.song("陈奕迅/U 87/05 浮夸.flac")

        result = self.files.delete([str(self.library / "陈奕迅/U 87/05 浮夸.flac")])

        self.assertEqual(["陈奕迅/U 87/05 浮夸.flac"], result["deleted"])
        self.assertEqual([], result["failed"])
        self.assertFalse((self.library / "陈奕迅/U 87/05 浮夸.flac").exists())
        self.assertTrue(
            (self.recycle / "陈奕迅/U 87/05 浮夸.flac").exists(),
            "回收站里必须保持原相对路径，否则恢复时不知道挪回哪",
        )

    def test_missing_file_is_reported_not_raised(self):
        result = self.files.delete([str(self.library / "不存在.flac")])

        self.assertEqual([], result["deleted"])
        self.assertEqual(1, len(result["failed"]))
        self.assertIn("不存在.flac", result["failed"][0]["path"])

    def test_one_bad_path_does_not_block_the_others(self):
        self.song("Adele/21/01 Hello.flac")

        result = self.files.delete([
            str(self.library / "不存在.flac"),
            str(self.library / "Adele/21/01 Hello.flac"),
        ])

        self.assertEqual(1, len(result["deleted"]))
        self.assertEqual(1, len(result["failed"]))

    def test_creates_recycle_root_on_first_delete(self):
        self.song("Adele/21/01 Hello.flac")
        self.assertFalse(self.recycle.exists())

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertTrue(self.recycle.is_dir())


# ─── 删完清空目录 ─────────────────────────────────────────


class PruneEmptyDirsTest(LibraryFilesTestCase):
    """只清「没有别的东西」的目录——宁可留空壳，也不能误删。"""

    def test_album_dir_left_with_only_nfo_is_removed(self):
        self.song("Adele/21/01 Hello.flac")
        (self.library / "Adele/21/album.nfo").write_text("<album/>")

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertFalse((self.library / "Adele/21").exists())
        self.assertTrue(
            (self.recycle / "Adele/21/album.nfo").exists(),
            "nfo 也要挪进回收站，不能直接删——恢复时要能一起回来",
        )

    def test_album_dir_with_another_song_is_left_alone(self):
        self.song("Adele/21/01 Hello.flac")
        self.song("Adele/21/02 Rolling in the Deep.flac")

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertTrue((self.library / "Adele/21/02 Rolling in the Deep.flac").exists())

    def test_album_dir_with_a_stray_file_is_left_alone(self):
        """哪怕只剩一个不是音频、也不是 .nfo 的文件，整个目录都不许动。"""
        self.song("Adele/21/01 Hello.flac")
        (self.library / "Adele/21/封面.jpg").write_bytes(b"jpg")

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertTrue((self.library / "Adele/21").is_dir())
        self.assertTrue((self.library / "Adele/21/封面.jpg").exists())

    def test_walks_up_and_prunes_artist_dir_too(self):
        self.song("Adele/21/01 Hello.flac")
        (self.library / "Adele/21/album.nfo").write_text("<album/>")
        (self.library / "Adele/artist.nfo").write_text("<artist/>")

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertFalse((self.library / "Adele").exists())
        self.assertTrue((self.recycle / "Adele/artist.nfo").exists())

    def test_stops_at_library_root(self):
        self.song("01 Hello.flac")

        self.files.delete([str(self.library / "01 Hello.flac")])

        self.assertTrue(self.library.is_dir(), "曲库根目录本身绝不能删")

    def test_artist_dir_with_another_album_is_left_alone(self):
        self.song("Adele/21/01 Hello.flac")
        self.song("Adele/25/01 Hello.flac")

        self.files.delete([str(self.library / "Adele/21/01 Hello.flac")])

        self.assertTrue((self.library / "Adele/25/01 Hello.flac").exists())


# ─── 改名 ────────────────────────────────────────────────


class RenameTest(LibraryFilesTestCase):
    def test_writes_all_three_fields(self):
        path = self.song("陈奕迅/U 87/05 浮夸.flac")

        self.files.rename(str(path), title="浮夸 (Live)", artist="陈奕迅", album="U 87")

        tags = read_tags(path)
        self.assertEqual("浮夸 (Live)", tags["title"][0])
        self.assertEqual("陈奕迅", tags["artist"][0])
        self.assertEqual("U 87", tags["album"][0])

    def test_only_touches_given_fields(self):
        path = self.song("Adele/21/01 Hello.flac", title="Hello", artist="Adele", album="21")

        self.files.rename(str(path), title="Hello (Live)")

        tags = read_tags(path)
        self.assertEqual("Hello (Live)", tags["title"][0])
        self.assertEqual("Adele", tags["artist"][0], "没传的字段不许动")
        self.assertEqual("21", tags["album"][0])

    def test_rejects_when_no_field_given(self):
        path = self.song("Adele/21/01 Hello.flac")
        with self.assertRaises(LibraryFilesError):
            self.files.rename(str(path))

    def test_rejects_missing_file(self):
        with self.assertRaises(LibraryFilesError):
            self.files.rename(str(self.library / "不存在.flac"), title="x")

    def test_rejects_non_audio_file(self):
        """改不动就明确报错，不许静默成功——用户会以为改完了。"""
        junk = self.library / "Adele/21/notes.txt"
        junk.parent.mkdir(parents=True, exist_ok=True)
        junk.write_text("我不是音频")

        with self.assertRaises(LibraryFilesError):
            self.files.rename(str(junk), title="x")

    def test_file_survives_the_edit(self):
        """写标签是原地改，不能把文件写没了。"""
        path = self.song("Adele/21/01 Hello.flac", title="Hello")
        before = path.stat().st_size

        self.files.rename(str(path), title="Hello (Live)")

        self.assertTrue(path.exists())
        self.assertGreater(path.stat().st_size, 0)
        self.assertLess(abs(path.stat().st_size - before), 4096)


# ─── 恢复 ────────────────────────────────────────────────


class RestoreTest(LibraryFilesTestCase):
    def test_restores_to_original_location(self):
        path = self.song("陈奕迅/U 87/05 浮夸.flac")
        self.files.delete([str(path)])

        result = self.files.restore(["陈奕迅/U 87/05 浮夸.flac"])

        self.assertEqual(["陈奕迅/U 87/05 浮夸.flac"], result["restored"])
        self.assertTrue(path.exists())
        self.assertFalse((self.recycle / "陈奕迅/U 87/05 浮夸.flac").exists())

    def test_recreates_missing_parent_dir(self):
        path = self.song("陈奕迅/U 87/05 浮夸.flac")
        self.files.delete([str(path)])
        # 删完目录已被清掉，恢复必须能把目录建回来
        self.assertFalse((self.library / "陈奕迅/U 87").exists())

        self.files.restore(["陈奕迅/U 87/05 浮夸.flac"])

        self.assertTrue(path.exists())

    def test_refuses_to_overwrite_existing_file(self):
        path = self.song("Adele/21/01 Hello.flac")
        self.files.delete([str(path)])
        self.song("Adele/21/01 Hello.flac", title="后来下的另一份")

        result = self.files.restore(["Adele/21/01 Hello.flac"])

        self.assertEqual([], result["restored"])
        self.assertEqual(1, len(result["failed"]))
        self.assertTrue(
            (self.recycle / "Adele/21/01 Hello.flac").exists(),
            "拒绝恢复时回收站里那份必须原样留着",
        )

    def test_restores_nfo_alongside_songs(self):
        path = self.song("Adele/21/01 Hello.flac")
        (self.library / "Adele/21/album.nfo").write_text("<album/>")
        self.files.delete([str(path)])

        self.files.restore(["Adele/21/01 Hello.flac", "Adele/21/album.nfo"])

        self.assertTrue(path.exists())
        self.assertTrue((self.library / "Adele/21/album.nfo").exists())

    def test_leaves_no_empty_dir_shell_in_recycle(self):
        """恢复之后，文件在回收站里占的目录壳也得消失，否则来回一趟积一个空目录。"""
        path = self.song("陈奕迅/U 87/05 浮夸.flac")
        self.files.delete([str(path)])

        self.files.restore(["陈奕迅/U 87/05 浮夸.flac"])

        self.assertFalse((self.recycle / "陈奕迅").exists())

    def test_keeps_recycle_dirs_that_still_hold_other_files(self):
        """回收站里还有别的歌时，目录不能跟着被删掉。"""
        keep = self.song("Adele/21/02 Rolling.flac")
        self.files.delete([str(keep)])
        self.files.delete([str(self.song("Adele/21/01 Hello.flac"))])

        self.files.restore(["Adele/21/01 Hello.flac"])

        self.assertTrue((self.recycle / "Adele/21/02 Rolling.flac").exists())


# ─── 回收站 ──────────────────────────────────────────────


class RecycleBinTest(LibraryFilesTestCase):
    def test_list_on_missing_dir_is_empty(self):
        self.assertEqual([], self.files.list_recycle())

    def test_list_reports_relative_path_and_size(self):
        path = self.song("Adele/21/01 Hello.flac")
        self.files.delete([str(path)])
        size = (self.recycle / "Adele/21/01 Hello.flac").stat().st_size

        entries = self.files.list_recycle()

        self.assertEqual(1, len(entries))
        self.assertEqual("Adele/21/01 Hello.flac", entries[0]["rel_path"].replace("\\", "/"))
        self.assertEqual(size, entries[0]["size"])
        self.assertIn("deleted_at", entries[0])

    def test_list_ignores_directories(self):
        path = self.song("Adele/21/01 Hello.flac")
        self.files.delete([str(path)])

        entries = self.files.list_recycle()

        self.assertTrue(all(e["rel_path"] for e in entries))

    def test_purge_removes_everything_and_reports_count(self):
        self.files.delete([str(self.song("Adele/21/01 Hello.flac"))])
        self.files.delete([str(self.song("Adele/21/02 Rolling.flac"))])

        purged = self.files.purge()

        self.assertEqual(2, purged)
        self.assertEqual([], self.files.list_recycle())

    def test_purge_on_missing_dir_is_zero(self):
        self.assertEqual(0, self.files.purge())

    def test_purge_leaves_library_alone(self):
        self.song("Adele/21/02 Rolling.flac")
        self.files.delete([str(self.song("Adele/21/01 Hello.flac"))])

        self.files.purge()

        self.assertTrue((self.library / "Adele/21/02 Rolling.flac").exists())


# ─── 删除 → 恢复 全流程（回收站的意义就在这条路上） ──────────


class RoundTripTest(LibraryFilesTestCase):
    def test_delete_then_restore_brings_everything_back(self):
        song = self.song("YELLOW黄宣/浮世击/03 羊皮先生.flac", title="羊皮先生")
        (self.library / "YELLOW黄宣/浮世击/album.nfo").write_text("<album/>")
        (self.library / "YELLOW黄宣/artist.nfo").write_text("<artist/>")
        before = sorted(
            str(p.relative_to(self.library)).replace("\\", "/")
            for p in self.library.rglob("*") if p.is_file()
        )

        self.files.delete([str(song)])
        self.files.restore([e["rel_path"].replace("\\", "/") for e in self.files.list_recycle()])

        after = sorted(
            str(p.relative_to(self.library)).replace("\\", "/")
            for p in self.library.rglob("*") if p.is_file()
        )
        self.assertEqual(before, after)
        self.assertEqual([], self.files.list_recycle())


if __name__ == "__main__":
    unittest.main()
