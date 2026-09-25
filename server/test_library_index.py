"""曲库索引的判定规则测试。

先例见 test_qqmusic_service.py：unittest + patch.object，只测外部行为。
本文件覆盖最集中的风险点——歌名/歌手的归一化与「是不是同一首」。
"""

import tempfile
import unittest
from pathlib import Path

from library_index import LibraryIndex, identity_from_file, normalize_text, same_song


class NormalizeTextTest(unittest.TestCase):
    """归一化只做「肉眼看着本来就是同一个字」的等价替换。

    括号及其内容绝不丢弃——那是区分 Live / Remix / 翻译版的关键。
    """

    def test_case_and_whitespace_differences_are_ignored(self):
        self.assertEqual(normalize_text("Someone Like You"), normalize_text("someone  like  you"))

    def test_fullwidth_letters_and_digits_become_halfwidth(self):
        self.assertEqual("abc123", normalize_text("ａｂｃ１２３"))

    def test_fullwidth_punctuation_becomes_halfwidth(self):
        self.assertEqual(normalize_text("for free?"), normalize_text("ｆｏｒ ｆｒｅｅ？"))

    def test_curly_quotes_match_straight_quotes(self):
        self.assertEqual(normalize_text('Luv(sic) (12" Remix)'), normalize_text("Luv(sic) (12” Remix)"))

    def test_ellipsis_matches_three_dots(self):
        self.assertEqual(normalize_text("Hello..."), normalize_text("Hello…"))

    def test_cjk_is_left_alone(self):
        self.assertEqual("ライラック (丁香)", normalize_text("ライラック (丁香)"))

    def test_brackets_separate_versions(self):
        self.assertNotEqual(normalize_text("晴天"), normalize_text("晴天 (Live)"))

    def test_sanitized_underscore_is_not_reversed(self):
        """下载器把 ? : " / 等清洗成了 _，是有损的，不猜着还原。

        这类文件靠内部标签兜底（全库 595 个文件都有标签），
        猜着还原会凭空制造误判。
        """
        self.assertNotEqual(normalize_text("For Free?"), normalize_text("For Free_"))


class SameSongTest(unittest.TestCase):
    """歌名必须完全相等；歌手按「分段后一边盖住另一边」判。

    分段而不是裸子串：`Tia` 不该因为碰巧是 `Tia Ray` 的子串就被认成同一个人。
    """

    def test_same_title_and_artist_matches(self):
        self.assertTrue(same_song("Only One", "BoA", "only one", "boa"))

    def test_different_artist_with_same_title_does_not_match(self):
        """翻唱 vs 原唱：同名但不同人，必须判成没有，否则用户下不了原唱。"""
        self.assertFalse(
            same_song("We Can't Stop", "Boyce Avenue", "We Can't Stop", "Miley Cyrus")
        )

    def test_collaboration_artist_covering_solo_artist_matches(self):
        self.assertTrue(same_song("Only One", "BoA", "Only One", "BoA/三浦大知"))

    def test_ampersand_separated_artists_match(self):
        self.assertTrue(
            same_song("Die With A Smile", "Lady Gaga", "Die With A Smile", "Lady Gaga & Bruno Mars")
        )

    def test_longer_artist_list_covering_short_one_matches_either_direction(self):
        self.assertTrue(
            same_song(
                "After Last Night",
                "Bruno Mars & Anderson .Paak & Silk Sonic",
                "After Last Night",
                "Silk Sonic",
            )
        )

    def test_partial_name_is_not_a_segment_match(self):
        self.assertFalse(same_song("Ray", "Tia", "Ray", "Tia Ray"))

    def test_missing_artist_falls_back_to_title_only(self):
        """文件名回退解析时可能取不到歌手，此时只按歌名判。"""
        self.assertTrue(same_song("灯火", "", "灯火", "Vaundy"))

    def test_versions_in_brackets_are_different_songs(self):
        self.assertFalse(same_song("晴天", "周杰伦", "晴天 (Live)", "周杰伦"))

    def test_blank_title_never_matches(self):
        """歌名都读不出来 = 判定不出来，按「没有」处理（宁可漏判）。"""
        self.assertFalse(same_song("", "周杰伦", "", "周杰伦"))

    def test_unknown_artist_does_not_match_everything(self):
        self.assertFalse(same_song("Only One", "BoA", "Only One", "Lady Gaga"))


class IdentityFromFileTest(unittest.TestCase):
    """判定钥匙：优先音频标签，标签缺失才回退解析文件名。

    标签优先不是锦上添花，是必须——下载器把文件名清洗过
    （`Mrs. GREEN APPLE` → `Mrs_ GREEN APPLE`、`For Free?` → `For Free_`），
    标签里存的才是原文。
    """

    def test_tags_are_used_when_present(self):
        self.assertEqual(
            ("Someone Like You", "Adele"),
            identity_from_file(
                "11 Someone Like You.flac",
                {"title": "Someone Like You", "artist": "Adele"},
            ),
        )

    def test_tag_keys_are_matched_case_insensitively(self):
        self.assertEqual(
            ("For Free?", "Kendrick Lamar"),
            identity_from_file(
                "02 For Free_.flac", {"TITLE": "For Free?", "ARTIST": "Kendrick Lamar"}
            ),
        )

    def test_tags_win_over_sanitized_filename(self):
        """文件名的点被清洗成了下划线，标签里是原文——必须用标签。"""
        self.assertEqual(
            ("ライラック (丁香)", "Mrs. GREEN APPLE"),
            identity_from_file(
                "Mrs_ GREEN APPLE - ライラック (丁香)_hires.flac",
                {"title": "ライラック (丁香)", "artist": "Mrs. GREEN APPLE"},
            ),
        )

    def test_albumartist_is_used_when_artist_is_missing(self):
        self.assertEqual(
            ("宗教", "椎名林檎"),
            identity_from_file("x.flac", {"title": "宗教", "albumartist": "椎名林檎"}),
        )

    def test_multi_value_tag_takes_the_first(self):
        self.assertEqual(
            ("宗教", "椎名林檎"),
            identity_from_file("x.flac", {"title": ["宗教"], "artist": ["椎名林檎"]}),
        )

    def test_falls_back_to_track_numbered_filename(self):
        self.assertEqual(("Someone Like You", ""), identity_from_file("11 Someone Like You.flac", {}))

    def test_disc_number_prefix_is_stripped(self):
        self.assertEqual(("Isn't She Lovely", ""), identity_from_file("2-11 Isn't She Lovely.flac", {}))

    def test_artist_title_filename_is_split(self):
        self.assertEqual(("beside you", "keshi"), identity_from_file("keshi - beside you.flac", {}))

    def test_quality_suffix_is_stripped(self):
        self.assertEqual(("magnolia", "keshi"), identity_from_file("keshi - magnolia_H.ogg", {}))

    def test_track_numbered_filename_keeps_dash_inside_title(self):
        """带 track 号的文件名不做 `歌手 - 歌名` 拆分，否则歌名里的 ` - ` 会被误切。"""
        self.assertEqual(("Love - Remastered", ""), identity_from_file("01 Love - Remastered.flac", {}))

    def test_sanitized_artist_in_filename_is_not_reversed(self):
        self.assertEqual(
            ("ライラック (丁香)", "Mrs_ GREEN APPLE"),
            identity_from_file("Mrs_ GREEN APPLE - ライラック (丁香)_hires.flac", {}),
        )

    def test_nothing_usable_yields_blank_identity(self):
        self.assertEqual(("", ""), identity_from_file("_hires.flac", {}))

    def test_tags_without_title_fall_back_to_filename(self):
        self.assertEqual(
            ("Someone Like You", ""), identity_from_file("11 Someone Like You.flac", {"title": "", "artist": ""})
        )


class LibraryIndexTest(unittest.TestCase):
    """索引：扫一遍曲库，之后回答「有没有这首歌」。

    读标签是外部边界（文件系统），所以注入假的读标签函数——
    测试不必在临时目录里造真的音频文件。
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.tags = {}   # 文件名 → 标签字典
        self.reads = []  # 实际被读了标签的文件名
        self.index = LibraryIndex(self.root, read_tags=self._read_tags)

    def _read_tags(self, path):
        self.reads.append(path.name)
        return self.tags.get(path.name, {})

    def _make(self, name, content=b"x"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def test_not_ready_until_first_refresh(self):
        self._make("11 Someone Like You.flac")
        self.assertFalse(self.index.ready)
        self.assertFalse(self.index.contains("Someone Like You", "Adele"))

    def test_contains_matches_scanned_song_by_tags(self):
        self._make("11 Someone Like You.flac")
        self.tags["11 Someone Like You.flac"] = {"title": "Someone Like You", "artist": "Adele"}
        self.index.refresh()
        self.assertTrue(self.index.ready)
        self.assertTrue(self.index.contains("Someone Like You", "Adele"))
        self.assertTrue(self.index.contains("someone  like  you", "adele"))

    def test_contains_rejects_song_not_in_library(self):
        self._make("11 Someone Like You.flac")
        self.index.refresh()
        self.assertFalse(self.index.contains("Rolling In The Deep", "Adele"))

    def test_falls_back_to_filename_when_tags_are_missing(self):
        self._make("keshi - beside you.flac")
        self.index.refresh()
        self.assertTrue(self.index.contains("beside you", "keshi"))

    def test_blank_title_is_never_contained(self):
        self._make("11 Someone Like You.flac")
        self.index.refresh()
        self.assertFalse(self.index.contains("", "Adele"))

    def test_walks_nested_directories(self):
        """真实曲库是 歌手/专辑/文件 三层。"""
        self._make("Adele/21 (Explicit)/11 Someone Like You.flac")
        self.tags["11 Someone Like You.flac"] = {"title": "Someone Like You", "artist": "Adele"}
        self.index.refresh()
        self.assertEqual(1, self.index.count)
        self.assertTrue(self.index.contains("Someone Like You", "Adele"))

    def test_non_audio_files_are_ignored(self):
        self._make("11 Someone Like You.flac")
        self._make("Adele/artist.nfo")
        self._make("Adele/21 (Explicit)/album.nfo")
        self.index.refresh()
        self.assertEqual(1, self.index.count)

    def test_unchanged_files_are_not_read_again(self):
        self._make("11 Someone Like You.flac")
        self.index.refresh()
        self.reads.clear()
        self.index.refresh()
        self.assertEqual([], self.reads)

    def test_changed_file_is_read_again(self):
        path = self._make("11 Someone Like You.flac", b"x")
        self.index.refresh()
        self.reads.clear()
        path.write_bytes(b"much longer content now")
        self.index.refresh()
        self.assertEqual(["11 Someone Like You.flac"], self.reads)

    def test_removed_file_drops_out_of_the_index(self):
        path = self._make("11 Someone Like You.flac")
        self.tags["11 Someone Like You.flac"] = {"title": "Someone Like You", "artist": "Adele"}
        self.index.refresh()
        path.unlink()
        self.index.refresh()
        self.assertEqual(0, self.index.count)
        self.assertFalse(self.index.contains("Someone Like You", "Adele"))

    def test_newly_added_file_is_picked_up(self):
        self.index.refresh()
        self._make("keshi - beside you.flac")
        self.index.refresh()
        self.assertTrue(self.index.contains("beside you", "keshi"))

    def test_note_downloaded_adds_without_losing_existing_entries(self):
        self._make("11 Someone Like You.flac")
        self.tags["11 Someone Like You.flac"] = {"title": "Someone Like You", "artist": "Adele"}
        self.index.refresh()
        self.index.note_downloaded(self._make("keshi - beside you.flac"))
        self.assertTrue(self.index.contains("beside you", "keshi"))
        self.assertTrue(self.index.contains("Someone Like You", "Adele"))

    def test_note_downloaded_on_a_missing_file_is_ignored(self):
        self.index.refresh()
        self.index.note_downloaded(self.root / "gone.flac")
        self.assertEqual(0, self.index.count)

    def test_missing_root_does_not_crash(self):
        index = LibraryIndex(self.root / "does-not-exist", read_tags=self._read_tags)
        index.refresh()
        self.assertTrue(index.ready)
        self.assertEqual(0, index.count)
        self.assertFalse(index.contains("Someone Like You", "Adele"))

    def test_a_song_that_lands_during_a_rescan_is_not_pruned(self):
        """重扫取完目录快照之后才落盘的歌，不能被这一轮重扫当成「已删除」清掉。

        worker 下完一首会立刻 note_downloaded，与后台重扫是并发的：
        **快照里没有它，不等于它不在库里。**
        """
        track = self._make("a.flac")
        self.tags["a.flac"] = {"title": "歌-a"}
        self.index.refresh()

        raced = []

        def read_tags_during_rescan(path):
            if path.name == "a.flac" and not raced:
                # 重扫正在读标签，就在这个空档里：新歌落盘 + 立刻并入索引
                raced.append(True)
                self.tags["b.flac"] = {"title": "歌-b"}
                self._make("b.flac")
                self.index.note_downloaded(self.root / "b.flac")
            return self.tags.get(path.name, {})

        track.write_bytes(b"changed")  # 内容变了 ⇒ 重扫必须重读标签，才制造得出空档
        self.index = LibraryIndex(self.root, read_tags=read_tags_during_rescan)
        self.index.refresh()

        self.assertTrue(raced, "前提不成立：重扫根本没去读标签")
        self.assertTrue(self.index.contains("歌-b", ""))

    def test_entries_lists_every_indexed_song_sorted(self):
        self._make("b.flac")
        self._make("a.flac")
        self.tags["a.flac"] = {"title": "Someone Like You", "artist": "Adele"}
        self.tags["b.flac"] = {"title": "beside you", "artist": "keshi"}
        self.index.refresh()
        self.assertEqual(
            [("beside you", "keshi"), ("Someone Like You", "Adele")],
            self.index.entries(),
        )


if __name__ == "__main__":
    unittest.main()
