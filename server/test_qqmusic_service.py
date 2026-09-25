import os
import sys
import tempfile
import time
import unittest
import warnings
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi import HTTPException


sys.path.insert(0, str(Path(__file__).resolve().parent))

with warnings.catch_warnings():
    # starlette 1.3 起抱怨 httpx 该换成 httpx2 了。能用，不为跑测试再装一个依赖。
    warnings.simplefilter("ignore")
    from fastapi.testclient import TestClient

import qqmusic_service as service
from library_files import LibraryFiles
from library_index import LibraryIndex, same_song
from test_library_files import minimal_flac


class CredentialHealthTest(unittest.TestCase):
    """磁盘凭证失效时必须自动从 QQ 音乐进程重提，而不是直接拿坏 key 去下载。"""

    def setUp(self):
        service._auth_cache = None

    def tearDown(self):
        service._auth_cache = None

    @patch.object(service.qmc2, "save_auth")
    @patch.object(service.qmc2, "extract_cookie_from_process")
    @patch.object(service, "_login_state", side_effect=[False, True])
    @patch.object(service.qmc2, "load_auth")
    def test_stale_disk_credential_is_replaced_from_process(
        self, load_auth, login_state, extract, save_auth
    ):
        load_auth.return_value = {"cookie": "qqmusic_key=deadbeef", "uin": "1"}
        extract.return_value = {"ok": True, "cookie": "qqmusic_key=FreshMixed", "uin": "1"}

        auth = service.get_auth()

        self.assertEqual("qqmusic_key=FreshMixed", auth["cookie"])
        extract.assert_called_once()
        save_auth.assert_called_once()

    @patch.object(service.qmc2, "extract_cookie_from_process")
    @patch.object(service, "_login_state", return_value=False)
    @patch.object(service.qmc2, "load_auth")
    def test_stale_disk_credential_still_usable_when_extract_fails(
        self, load_auth, login_state, extract
    ):
        load_auth.return_value = {"cookie": "qqmusic_key=deadbeef", "uin": "1"}
        extract.return_value = {"ok": False, "error": "QQMusic.exe not running"}

        auth = service.get_auth()

        self.assertEqual("qqmusic_key=deadbeef", auth["cookie"])

    @patch.object(service.qmc2, "extract_cookie_from_process")
    @patch.object(service, "_login_state", return_value=True)
    @patch.object(service.qmc2, "load_auth")
    def test_valid_disk_credential_is_used_without_extraction(
        self, load_auth, login_state, extract
    ):
        load_auth.return_value = {"cookie": "qqmusic_key=GoodMixed", "uin": "1"}

        auth = service.get_auth()

        self.assertEqual("qqmusic_key=GoodMixed", auth["cookie"])
        extract.assert_not_called()

    @patch.object(service.qmc2, "extract_cookie_from_process")
    @patch.object(service, "_login_state", return_value=None)
    @patch.object(service.qmc2, "load_auth")
    def test_unknown_login_state_keeps_disk_credential(
        self, load_auth, login_state, extract
    ):
        """探测请求网络异常时不能误判为失效，否则每次都会扫进程内存。"""
        load_auth.return_value = {"cookie": "qqmusic_key=GoodMixed", "uin": "1"}

        auth = service.get_auth()

        self.assertEqual("qqmusic_key=GoodMixed", auth["cookie"])
        extract.assert_not_called()


class LoginStateProbeTest(unittest.TestCase):
    @patch.object(service.qmc2, "_musicu_post")
    def test_logged_in_code_is_true(self, post):
        post.return_value = {"req_1": {"code": 40000}}
        self.assertIs(True, service._login_state({"cookie": "c", "uin": "1"}))

    @patch.object(service.qmc2, "_musicu_post")
    def test_not_logged_in_code_is_false(self, post):
        post.return_value = {"req_1": {"code": 1000}}
        self.assertIs(False, service._login_state({"cookie": "c", "uin": "1"}))

    @patch.object(service.qmc2, "_musicu_post", side_effect=OSError("boom"))
    def test_network_error_is_unknown(self, post):
        self.assertIsNone(service._login_state({"cookie": "c", "uin": "1"}))

    def test_missing_fields_is_false(self):
        self.assertIs(False, service._login_state({"cookie": "", "uin": ""}))


class DownloadRecoveryTest(unittest.TestCase):
    @patch.object(service, "_login_state", return_value=False)
    @patch.object(service, "_refresh_credential_from_process")
    @patch.object(service.dl, "process_song")
    def test_retries_once_with_fresh_credential_when_no_link(
        self, process_song, refresh, login_state
    ):
        cookies = []
        fresh = {"cookie": "qqmusic_key=Fresh", "uin": "1"}

        def fake_process_song(song_mid, quality, auth, output_root, ffmpeg):
            cookies.append(auth["cookie"])
            if len(cookies) == 1:
                print(service.NO_LINK_MARKER)
                return False
            return True

        process_song.side_effect = fake_process_song
        refresh.return_value = fresh

        ok, captured = service._download_with_recovery(
            "000gtopG1e7HB2", "flac", {"cookie": "qqmusic_key=Stale", "uin": "1"}, "ffmpeg"
        )

        self.assertTrue(ok)
        login_state.assert_called_once()
        self.assertEqual(["qqmusic_key=Stale", "qqmusic_key=Fresh"], cookies)

    @patch.object(service, "_login_state", return_value=True)
    @patch.object(service, "_refresh_credential_from_process")
    @patch.object(service.dl, "process_song")
    def test_does_not_refresh_when_credential_still_valid(self, process_song, refresh, login_state):
        def fake_process_song(song_mid, quality, auth, output_root, ffmpeg):
            print(service.NO_LINK_MARKER)
            return False

        process_song.side_effect = fake_process_song

        ok, captured = service._download_with_recovery(
            "000gtopG1e7HB2", "flac", {"cookie": "qqmusic_key=Stale", "uin": "1"}, "ffmpeg"
        )

        self.assertFalse(ok)
        refresh.assert_not_called()

    @patch.object(service, "_login_state", return_value=False)
    @patch.object(service, "_refresh_credential_from_process")
    @patch.object(service.dl, "process_song", return_value=False)
    def test_does_not_refresh_on_unrelated_failure(self, process_song, refresh, login_state):
        ok, captured = service._download_with_recovery(
            "000gtopG1e7HB2", "flac", {"cookie": "qqmusic_key=Stale", "uin": "1"}, "ffmpeg"
        )

        self.assertFalse(ok)
        refresh.assert_not_called()

    @patch.object(service, "_login_state", return_value=False)
    @patch.object(service, "_refresh_credential_from_process", return_value=None)
    @patch.object(service.dl, "process_song")
    def test_no_retry_when_refresh_unavailable(self, process_song, refresh, login_state):
        def fake_process_song(song_mid, quality, auth, output_root, ffmpeg):
            print(service.NO_LINK_MARKER)
            return False

        process_song.side_effect = fake_process_song

        ok, captured = service._download_with_recovery(
            "000gtopG1e7HB2", "flac", {"cookie": "qqmusic_key=Stale", "uin": "1"}, "ffmpeg"
        )

        self.assertFalse(ok)
        self.assertEqual(1, process_song.call_count)


class SearchFallbackTest(unittest.TestCase):
    @patch.object(service.qmc2, "search_songs", return_value=[])
    @patch("requests.get")
    def test_song_search_falls_back_when_desktop_api_returns_empty(self, get, _desktop_search):
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "code": 0,
            "data": {
                "song": {
                    "list": [
                        {
                            "songname": "搁浅",
                            "songmid": "001xd0HI0X9GNq",
                            "strMediaMid": "001xd0HI0X9GNq",
                            "singer": [{"name": "周杰伦"}],
                            "albumname": "七里香",
                            "sizeflac": 123,
                            "size320": 45,
                            "size128": 6,
                        }
                    ]
                }
            },
        }
        get.return_value = response

        songs = service._search_songs("周杰伦", {"cookie": "cookie", "uin": "1"}, 20)

        self.assertEqual("搁浅", songs[0]["title"])
        self.assertEqual("001xd0HI0X9GNq", songs[0]["song_mid"])
        self.assertEqual(["flac", "320", "128"], songs[0]["available_qualities"])

    @patch.object(service.qmc2, "search_albums", return_value=[])
    @patch("requests.get")
    def test_album_search_falls_back_when_desktop_api_returns_empty(self, get, _desktop_search):
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "code": 0,
            "data": {
                "album": {
                    "list": [
                        {
                            "albumMID": "000MkMni19ClKG",
                            "albumID": 42,
                            "albumName": "七里香",
                            "singerName": "周杰伦",
                            "publicTime": "2004-08-03",
                            "song_count": 10,
                        }
                    ]
                }
            },
        }
        get.return_value = response

        albums = service._search_albums("周杰伦", {"cookie": "cookie", "uin": "1"}, 20)

        self.assertEqual("七里香", albums[0]["album_name"])
        self.assertEqual("000MkMni19ClKG", albums[0]["album_mid"])


ALBUM_MID = "000MkMni19ClKG"
ALBUM_TRACKS = [
    {"song_mid": "000trackA1", "name": "第一首"},
    {"song_mid": "000trackB2", "name": "第二首"},
    {"song_mid": "000trackC3", "name": "第三首"},
]


class AlbumSongsRouteTest(unittest.TestCase):
    """曲目列表要带歌名，App 的选曲弹窗才有人看得懂的条目。"""

    @patch.object(service, "_album_song_details", return_value=[dict(t) for t in ALBUM_TRACKS])
    @patch.object(service, "_auth_or_503", return_value={"cookie": "c", "uin": "1"})
    def test_returns_song_names_alongside_mids(self, auth, details):
        result = service.album_songs(ALBUM_MID)

        self.assertEqual([t["name"] for t in ALBUM_TRACKS], [s["name"] for s in result["songs"]])
        self.assertEqual([t["song_mid"] for t in ALBUM_TRACKS], result["song_mids"])

    def test_invalid_album_mid_is_rejected(self):
        with self.assertRaises(HTTPException):
            service.album_songs("bad-mid!")


class AlbumPartialDownloadTest(unittest.TestCase):
    """专辑下载支持只下选中部分曲目（请求体 song_mids）。"""

    def setUp(self):
        self.enqueued = []

        def fake_enqueue(song_mid, quality, title=None):
            self.enqueued.append((song_mid, quality, title))
            return f"job-{len(self.enqueued)}"

        self.enqueue_patch = patch.object(service, "_enqueue", side_effect=fake_enqueue)
        self.enqueue = self.enqueue_patch.start()
        self.addCleanup(self.enqueue_patch.stop)
        self.auth_patch = patch.object(
            service, "_auth_or_503", return_value={"cookie": "c", "uin": "1"}
        )
        self.addCleanup(self.auth_patch.stop)
        self.auth_patch.start()

    def _album_songs(self):
        return patch.object(
            service, "_album_song_details", return_value=[dict(t) for t in ALBUM_TRACKS]
        )

    def test_only_selected_tracks_are_enqueued_in_album_order(self):
        with self._album_songs():
            # 故意乱序传，服务端应按专辑内顺序入队
            result = service.download_album(
                service.DownloadAlbumBody(
                    album_mid=ALBUM_MID, quality="flac",
                    song_mids=["000trackC3", "000trackA1"],
                )
            )

        self.assertEqual(2, result["count"])
        self.assertEqual(
            [("000trackA1", "flac", "第一首"), ("000trackC3", "flac", "第三首")],
            self.enqueued,
        )

    def test_whole_album_when_no_selection_given(self):
        with self._album_songs():
            result = service.download_album(
                service.DownloadAlbumBody(album_mid=ALBUM_MID, quality="flac")
            )

        self.assertEqual(3, result["count"])
        self.assertEqual([t["song_mid"] for t in ALBUM_TRACKS], [e[0] for e in self.enqueued])

    def test_empty_selection_is_rejected(self):
        with self._album_songs():
            with self.assertRaises(HTTPException) as ctx:
                service.download_album(
                    service.DownloadAlbumBody(
                        album_mid=ALBUM_MID, quality="flac", song_mids=[]
                    )
                )

        self.assertEqual(400, ctx.exception.status_code)
        self.enqueue.assert_not_called()

    def test_selection_outside_album_is_rejected(self):
        with self._album_songs():
            with self.assertRaises(HTTPException) as ctx:
                service.download_album(
                    service.DownloadAlbumBody(
                        album_mid=ALBUM_MID, quality="flac", song_mids=["000otherSong"]
                    )
                )

        self.assertEqual(400, ctx.exception.status_code)
        self.enqueue.assert_not_called()

    def test_blank_mids_are_ignored(self):
        """空串不能当成「选中了一首不存在的歌」误报，也不能被当成有效选择。"""
        with self._album_songs():
            with self.assertRaises(HTTPException) as ctx:
                service.download_album(
                    service.DownloadAlbumBody(
                        album_mid=ALBUM_MID, quality="flac", song_mids=["", ""]
                    )
                )

        self.assertEqual(400, ctx.exception.status_code)
        self.enqueue.assert_not_called()


class FakeLibraryIndex:
    """手写假索引：只假装「库里存了哪几首」，判定规则一律用真的。

    刻意不自己写匹配逻辑——假索引要是和真索引判得不一样，
    「缺歌手会不会误判」这类测试就是空的（真索引缺歌手时会只按歌名判）。
    """

    def __init__(self, entries=(), ready=True):
        self.entries = set(entries)
        self.ready = ready
        self.count = len(self.entries)

    def contains(self, title, artist):
        return any(same_song(title, artist, t, a) for t, a in self.entries)


class LibraryFlagTestBase(unittest.TestCase):
    """公共脚手架：假索引 + 假凭证 + 任务表的增删清理。"""

    def setUp(self):
        self._jobs_before = set(service.jobs)
        self.addCleanup(self._drop_new_jobs)
        patch.object(service, "_auth_or_503", return_value={"cookie": "c", "uin": "1"}).start()
        self.addCleanup(patch.stopall)

    def _drop_new_jobs(self):
        for job_id in list(service.jobs):
            if job_id not in self._jobs_before:
                del service.jobs[job_id]

    def _use_index(self, *entries):
        """假索引：库里就这几首。"""
        patch.object(service, "_library_index", FakeLibraryIndex(entries)).start()

    def _use_pending_index(self, *entries):
        """真索引，但从没扫描过——服务刚起来还在扫曲库的那十几秒。

        真索引此时一律回 False；用假索引测就没有这个保证了。
        """
        patch.object(service, "_library_index", LibraryIndex(self._never_scanned)).start()

    def _use_broken_index(self):
        index = Mock()
        index.contains.side_effect = RuntimeError("boom")
        patch.object(service, "_library_index", index).start()

    # 不存在的目录：LibraryIndex 构造不碰磁盘，contains 一律 False
    _never_scanned = Path("no-such-library-root")

    def _add_job(self, song_mid, status):
        job_id = f"job-{song_mid}"
        with service.state_lock:
            service.jobs[job_id] = {
                "job_id": job_id, "song_mid": song_mid, "quality": "flac", "title": None,
                "status": status, "created_at": 0, "error": None,
                "final_path": None, "log": None,
            }
        return job_id


class SearchFlagTest(LibraryFlagTestBase):
    """搜索结果每首歌带 in_library / downloading，App 才能把已有的置灰。"""

    SONGS = [
        {"title": "晴天", "singer": "周杰伦", "album": "叶惠美", "song_mid": "000jing"},
        {"title": "Hello", "singer": "Adele", "album": "25", "song_mid": "000hello"},
    ]

    def _search(self, songs=None):
        songs = self.SONGS if songs is None else songs
        with patch.object(service, "_search_songs", return_value=[dict(s) for s in songs]):
            return service.search("x")["songs"]

    def test_marks_song_already_in_library_and_leaves_others_alone(self):
        self._use_index(("晴天", "周杰伦"))

        songs = self._search()

        self.assertEqual([True, False], [s["in_library"] for s in songs])
        self.assertEqual([False, False], [s["downloading"] for s in songs])

    def test_matching_uses_the_artist_not_just_the_title(self):
        """同名不同人（翻唱、同名曲）不能被当成「已有」。"""
        self._use_index(("晴天", "周杰伦"))

        songs = self._search([
            {"title": "晴天", "singer": "周杰伦", "song_mid": "000a"},
            {"title": "晴天", "singer": "其他歌手", "song_mid": "000b"},
        ])

        self.assertEqual({"000a": True, "000b": False},
                         {s["song_mid"]: s["in_library"] for s in songs})

    def test_marks_song_currently_downloading(self):
        self._use_index()
        self._add_job("000hello", "running")
        self._add_job("000jing", "done")

        songs = self._search()

        self.assertEqual({"000jing": False, "000hello": True},
                         {s["song_mid"]: s["downloading"] for s in songs})

    def test_queued_job_counts_as_downloading(self):
        self._use_index()
        self._add_job("000jing", "queued")

        songs = self._search()

        self.assertEqual([True, False], [s["downloading"] for s in songs])

    def test_nothing_is_marked_when_the_index_is_not_ready(self):
        """服务刚起还在扫曲库：一律按「没有」处理，照常出结果不报错。"""
        self._use_pending_index(("晴天", "周杰伦"))

        songs = self._search()

        self.assertEqual([False, False], [s["in_library"] for s in songs])

    def test_song_without_a_singer_is_not_marked(self):
        """QQ 没回歌手时只按歌名判是猜——库里可能有同名不同人的。

        按铁律一律当「没有」：漏判只是多下一次。
        """
        self._use_index(("晴天", "周杰伦"))

        songs = self._search([
            {"title": "晴天", "singer": "", "song_mid": "000a"},
            {"title": "晴天", "song_mid": "000b"},
        ])

        self.assertEqual([False, False], [s["in_library"] for s in songs])

    def test_a_broken_index_does_not_break_search(self):
        self._use_broken_index()

        songs = self._search()

        self.assertEqual([False, False], [s["in_library"] for s in songs])


class AlbumSongsFlagTest(LibraryFlagTestBase):
    """选曲弹窗的每一行都要能看出在不在库、在不在下。"""

    TRACKS = [
        {"song_mid": "000a", "name": "晴天", "singer": "周杰伦"},
        {"song_mid": "000b", "name": "Hello", "singer": "Adele"},
    ]

    def _album_songs(self, tracks=None):
        tracks = self.TRACKS if tracks is None else tracks
        with patch.object(service, "_album_song_details", return_value=[dict(t) for t in tracks]):
            return service.album_songs(ALBUM_MID)

    def test_marks_each_track(self):
        self._use_index(("晴天", "周杰伦"))
        self._add_job("000b", "queued")

        result = self._album_songs()

        self.assertEqual({"000a": True, "000b": False},
                         {s["song_mid"]: s["in_library"] for s in result["songs"]})
        self.assertEqual({"000a": False, "000b": True},
                         {s["song_mid"]: s["downloading"] for s in result["songs"]})
        self.assertEqual(["000a", "000b"], result["song_mids"])

    def test_matching_uses_the_artist_not_just_the_title(self):
        self._use_index(("晴天", "周杰伦"))

        result = self._album_songs([
            {"song_mid": "000a", "name": "晴天", "singer": "周杰伦"},
            {"song_mid": "000b", "name": "晴天", "singer": "其他歌手"},
        ])

        self.assertEqual({"000a": True, "000b": False},
                         {s["song_mid"]: s["in_library"] for s in result["songs"]})

    def test_nothing_is_marked_when_the_index_is_not_ready(self):
        self._use_pending_index(("晴天", "周杰伦"))

        result = self._album_songs()

        self.assertEqual([False, False], [s["in_library"] for s in result["songs"]])

    @patch.object(service.qmc2, "_musicu_post")
    def test_track_details_carry_the_singer(self, post):
        """有歌手才能分辨「同名不同人」；接口没给就退回只按歌名判。"""
        post.return_value = {
            "req_1": {
                "data": {
                    "totalNum": 2,
                    "songList": [
                        {"songInfo": {"mid": "000a", "name": "晴天",
                                      "singer": [{"name": "周杰伦"}]}},
                        {"songInfo": {"mid": "000b", "name": "Hello"}},
                    ],
                }
            }
        }

        details = service._album_song_details(ALBUM_MID, "cookie", "1")

        self.assertEqual(
            [{"song_mid": "000a", "name": "晴天", "singer": "周杰伦"},
             {"song_mid": "000b", "name": "Hello", "singer": ""}],
            details,
        )


SINGLE = {"song_mid": "000jing", "quality": "flac", "title": "晴天", "artist": "周杰伦"}


class SingleDownloadSkipTest(LibraryFlagTestBase):
    """单调已在库时默认不入队；要重下必须显式带 force。"""

    def setUp(self):
        super().setUp()
        self.enqueued = []
        patch.object(service, "_enqueue", side_effect=self._fake_enqueue).start()

    def _fake_enqueue(self, song_mid, quality, title=None):
        self.enqueued.append((song_mid, quality, title))
        return f"job-{len(self.enqueued)}"

    def test_downloads_when_the_song_is_not_in_the_library(self):
        self._use_index(("别的歌", "别人"))

        result = service.download(service.DownloadBody(**SINGLE))

        self.assertEqual({"job_id": "job-1", "skipped": False}, result)
        self.assertEqual([("000jing", "flac", "晴天")], self.enqueued)

    def test_skips_song_already_in_the_library(self):
        self._use_index(("晴天", "周杰伦"))

        result = service.download(service.DownloadBody(**SINGLE))

        self.assertTrue(result["skipped"])
        self.assertEqual([], self.enqueued)

    def test_skipped_response_keeps_the_shape_of_a_normal_one(self):
        """老客户端只认 job_id，字段少了会当成「返回了无法识别的数据」。"""
        self._use_index(("晴天", "周杰伦"))
        skipped = service.download(service.DownloadBody(**SINGLE))

        self._use_index()
        normal = service.download(service.DownloadBody(**SINGLE))

        self.assertEqual(set(normal), set(skipped))
        self.assertIsInstance(skipped["job_id"], str)

    def test_force_downloads_a_song_already_in_the_library(self):
        self._use_index(("晴天", "周杰伦"))

        result = service.download(service.DownloadBody(**SINGLE, force=True))

        self.assertFalse(result["skipped"])
        self.assertEqual([("000jing", "flac", "晴天")], self.enqueued)

    def test_old_client_without_title_is_never_skipped(self):
        """老客户端不传歌名，判定不出来 ⇒ 按「没有」处理，照常下载。"""
        self._use_index(("晴天", "周杰伦"))

        result = service.download(service.DownloadBody(song_mid="000jing", quality="flac"))

        self.assertFalse(result["skipped"])
        self.assertEqual(1, len(self.enqueued))

    def test_client_without_artist_is_never_skipped(self):
        """只有歌名是猜——库里那首同名的可能是别人的歌，不能拿它挡住这次下载。

        （现有 App 的请求体里还没有 artist 字段，正是这条在兜底。）
        """
        self._use_index(("晴天", "别的歌手"))

        result = service.download(
            service.DownloadBody(song_mid="000jing", quality="flac", title="晴天")
        )

        self.assertFalse(result["skipped"])
        self.assertEqual(1, len(self.enqueued))

    def test_downloads_when_the_index_is_not_ready(self):
        self._use_pending_index(("晴天", "周杰伦"))

        result = service.download(service.DownloadBody(**SINGLE))

        self.assertFalse(result["skipped"])
        self.assertEqual(1, len(self.enqueued))

    def test_a_broken_index_never_blocks_a_download(self):
        self._use_broken_index()

        result = service.download(service.DownloadBody(**SINGLE))

        self.assertFalse(result["skipped"])
        self.assertEqual(1, len(self.enqueued))


class AlbumDownloadSkipTest(LibraryFlagTestBase):
    """整张下载只下缺的，并把跳过数说出来。"""

    TRACKS = [
        {"song_mid": "000a", "name": "晴天", "singer": "周杰伦"},
        {"song_mid": "000b", "name": "Hello", "singer": "Adele"},
        {"song_mid": "000c", "name": "灯火", "singer": "Vaundy"},
    ]

    def setUp(self):
        super().setUp()
        self.enqueued = []
        patch.object(service, "_enqueue", side_effect=self._fake_enqueue).start()

    def _fake_enqueue(self, song_mid, quality, title=None):
        self.enqueued.append(song_mid)
        return f"job-{len(self.enqueued)}"

    def _download(self, **body):
        with patch.object(
            service, "_album_song_details", return_value=[dict(t) for t in self.TRACKS]
        ):
            return service.download_album(
                service.DownloadAlbumBody(album_mid=ALBUM_MID, quality="flac", **body)
            )

    def test_whole_album_skips_tracks_already_in_the_library(self):
        self._use_index(("晴天", "周杰伦"), ("灯火", "Vaundy"))

        result = self._download()

        self.assertEqual(1, result["count"])
        self.assertEqual(2, result["skipped_count"])
        self.assertEqual(["000b"], self.enqueued)

    def test_whole_album_with_everything_in_the_library_is_not_an_error(self):
        """整张都在库是正常结果，不是错误——界面要说「已跳过 3 首」。"""
        self._use_index(("晴天", "周杰伦"), ("Hello", "Adele"), ("灯火", "Vaundy"))

        result = self._download()

        self.assertEqual(0, result["count"])
        self.assertEqual(3, result["skipped_count"])
        self.assertEqual([], result["job_ids"])
        self.assertEqual([], self.enqueued)

    def test_force_downloads_the_whole_album(self):
        self._use_index(("晴天", "周杰伦"), ("Hello", "Adele"), ("灯火", "Vaundy"))

        result = self._download(force=True)

        self.assertEqual(3, result["count"])
        self.assertEqual(0, result["skipped_count"])
        self.assertEqual(["000a", "000b", "000c"], self.enqueued)

    def test_explicit_selection_is_never_skipped(self):
        """用户一首首勾过的，勾选本身就是强制——不能再自作主张跳过。"""
        self._use_index(("晴天", "周杰伦"), ("灯火", "Vaundy"))

        result = self._download(song_mids=["000a", "000b"])

        self.assertEqual(2, result["count"])
        self.assertEqual(0, result["skipped_count"])
        self.assertEqual(["000a", "000b"], self.enqueued)

    def test_tracks_without_a_singer_are_never_skipped(self):
        """接口没回歌手时只按歌名判是猜——按铁律一律放行。"""
        self._use_index(("晴天", "周杰伦"), ("Hello", "Adele"), ("灯火", "Vaundy"))
        named = [{"song_mid": t["song_mid"], "name": t["name"]} for t in self.TRACKS]

        with patch.object(service, "_album_song_details", return_value=named):
            result = service.download_album(
                service.DownloadAlbumBody(album_mid=ALBUM_MID, quality="flac")
            )

        self.assertEqual(3, result["count"])
        self.assertEqual(0, result["skipped_count"])

    def test_nothing_is_skipped_when_the_index_is_not_ready(self):
        self._use_pending_index(("晴天", "周杰伦"), ("Hello", "Adele"), ("灯火", "Vaundy"))

        result = self._download()

        self.assertEqual(3, result["count"])
        self.assertEqual(0, result["skipped_count"])


class LibraryIndexWiringTest(unittest.TestCase):
    """健康接口自报索引状态；下完的歌立刻并入索引。"""

    def test_health_reports_the_index_status(self):
        with patch.object(service, "_library_index") as index:
            index.ready = True
            index.count = 596

            result = service.health()

        self.assertEqual(str(service.LIBRARY_ROOT), result["library"]["root"])
        self.assertTrue(result["library"]["ready"])
        self.assertEqual(596, result["library"]["count"])
        self.assertEqual(str(service.LIBRARY_ROOT), result["library_root"])

    def test_health_reports_scanning_before_the_first_scan(self):
        """服务刚起来还没扫完：界面靠这个显示「扫描中…」。"""
        result = service.health()

        self.assertFalse(result["library"]["ready"])
        self.assertEqual(0, result["library"]["count"])

    def test_finished_download_is_merged_into_the_index_immediately(self):
        path = str(Path(service.LIBRARY_ROOT) / "周杰伦/叶惠美/03 晴天.flac")
        with patch.object(service, "_library_index") as index:
            service._note_downloaded(path)

        index.note_downloaded.assert_called_once_with(Path(path))

    def test_a_broken_index_never_fails_a_successful_download(self):
        """索引是贴心助手，它的 bug 不能让下载成功的任务被标成失败。"""
        with patch.object(service, "_library_index") as index:
            index.note_downloaded.side_effect = RuntimeError("boom")

            service._note_downloaded("E:/file/Te_Music/x.flac")  # 不应抛出

    def test_nothing_happens_when_the_download_left_no_path(self):
        with patch.object(service, "_library_index") as index:
            service._note_downloaded(None)

        index.note_downloaded.assert_not_called()


# ─── 曲库文件操作：密钥与索引联动（工单 02） ────────────────

# 五个危险接口。带上合法密钥调用它们是真的会动文件的，所以
# FileOpsKeyTest 里把它们全指向临时目录——防的是以后有人加一条
# 「合法密钥也能过」的用例时，purge 把用户回收站真清空。
LIBRARY_CALLS = [
    ("post", "/api/library/delete", {"paths": []}),
    ("post", "/api/library/rename", {"path": "x", "title": "y"}),
    ("get", "/api/library/recycle", None),
    ("post", "/api/library/recycle/restore", {"paths": []}),
    ("post", "/api/library/recycle/purge", None),
]

LIBRARY_ROUTES = {
    "/api/library/delete",
    "/api/library/rename",
    "/api/library/recycle",
    "/api/library/recycle/restore",
    "/api/library/recycle/purge",
}

TEST_KEY = "test-key-please-ignore"


class FileOpsKeyTest(unittest.TestCase):
    """危险接口有锁，搜歌下载没有——密钥对不上时后者必须照常能用。"""

    KEY = TEST_KEY

    def setUp(self):
        self.addCleanup(patch.stopall)  # 先注册：setUp 中途失败也不能把 patch 漏给后面的测试
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        patch.object(service, "_file_ops_key", self.KEY).start()
        patch.object(service, "_library_files",
                     LibraryFiles(root / "Te_Music", root / "Te_Music_deleted")).start()
        self.client = TestClient(service.app)

    def _call(self, method, path, body, key):
        headers = {} if key is None else {"X-Pulsar-Key": key}
        # GET 不接受 json 参数（httpx 会直接抛 TypeError），只有 POST 带 body
        extra = {} if method == "get" else {"json": body}
        return getattr(self.client, method)(path, headers=headers, **extra)

    def test_missing_key_is_rejected_on_every_dangerous_route(self):
        for method, path, body in LIBRARY_CALLS:
            with self.subTest(path=path):
                self.assertEqual(401, self._call(method, path, body, None).status_code)

    def test_wrong_key_is_rejected_on_every_dangerous_route(self):
        for method, path, body in LIBRARY_CALLS:
            with self.subTest(path=path):
                self.assertEqual(401, self._call(method, path, body, "not-the-key").status_code)

    def test_rejection_tells_the_user_what_to_do(self):
        detail = self._call("get", "/api/library/recycle", None, "wrong").json()["detail"]

        self.assertIn("密钥", detail)

    def test_a_non_ascii_key_is_a_401_not_a_500(self):
        """HTTP 头能塞进非 ASCII 字节（裸 socket 就能发），starlette 按 latin-1 解出来
        就是一串非 ASCII 字符；比较若直接用 str，compare_digest 会抛 TypeError，
        401 就变成了 500。"""
        response = self.client.get("/api/library/recycle",
                                   headers={"X-Pulsar-Key": b"\xe5\xaf\x86\xe9\x92\xa5"})

        self.assertEqual(401, response.status_code)

    def test_right_key_gets_through(self):
        response = self._call("get", "/api/library/recycle", None, self.KEY)

        self.assertEqual(200, response.status_code)
        self.assertEqual({"files": [], "total_size": 0}, response.json())

    def test_health_needs_no_key(self):
        self.assertEqual(200, self.client.get("/api/health").status_code)


class FileOpsKeyScopeTest(unittest.TestCase):
    """密钥只挂危险接口。挂错地方 = 哪天密钥丢了连歌都下不了。"""

    def test_exactly_the_library_routes_are_behind_the_key(self):
        protected = {
            route.path
            for route in service.app.routes
            for dependency in getattr(route, "dependencies", [])
            if dependency.dependency is service.require_key
        }

        self.assertEqual(LIBRARY_ROUTES, protected)


class LibraryIndexRefreshTest(unittest.TestCase):
    """删/改之后索引必须立刻跟上。

    索引原本假设「曲库只增不改」。不刷新的话，删完一首想重下同一首，
    会被自己的去重挡下来——「我想下，它不让」是最难查的那种 bug。
    """

    def setUp(self):
        self.addCleanup(patch.stopall)  # 先注册：setUp 中途失败也不能把 patch 漏给后面的测试
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.library = root / "Te_Music"
        self.recycle = root / "Te_Music_deleted"
        self.library.mkdir()
        self.song = self.library / "周杰伦/叶惠美/03 晴天.flac"
        self.song.parent.mkdir(parents=True)
        self.song.write_bytes(minimal_flac())

        files = LibraryFiles(self.library, self.recycle)
        files.rename(str(self.song), title="晴天", artist="周杰伦")
        # refresh() 靠 (mtime, size) 跳过没变的文件，而改标签**常常一字节都不改大小**
        # （mutagen 会把 Vorbis 块补齐）。两次改名要是落在同一个时间戳刻度里，
        # 索引就会误判「没变」——测试不能赌时钟抖动，把初始时间戳往前拨。
        # 真实使用中两次操作隔着几百毫秒以上，撞不上。
        long_ago = time.time() - 5
        os.utime(self.song, (long_ago, long_ago))
        self.index = LibraryIndex(self.library)
        self.index.refresh()
        patch.object(service, "_file_ops_key", TEST_KEY).start()
        patch.object(service, "_library_index", self.index).start()
        patch.object(service, "_library_files", files).start()
        self.assertTrue(self.index.contains("晴天", "周杰伦"), "前置条件：索引里先得有这首")

    def _post(self, path, body):
        return TestClient(service.app).post(path, json=body, headers={"X-Pulsar-Key": TEST_KEY})

    def _delete(self):
        return service.library_delete(
            service.LibraryPathsBody(paths=[str(self.song)])
        )["deleted"]

    def test_delete_drops_the_song_from_the_index_immediately(self):
        self.assertEqual(["周杰伦/叶惠美/03 晴天.flac"], self._delete())

        self.assertFalse(self.index.contains("晴天", "周杰伦"))

    def test_rename_shows_up_in_the_index_immediately(self):
        service.library_rename(
            service.LibraryRenameBody(path=str(self.song), title="晴天 (Live)")
        )

        self.assertFalse(self.index.contains("晴天", "周杰伦"))
        self.assertTrue(self.index.contains("晴天 (Live)", "周杰伦"))

    def test_restore_puts_the_song_back_into_the_index(self):
        restored = self._delete()

        service.library_restore(service.LibraryPathsBody(paths=restored))

        self.assertTrue(self.index.contains("晴天", "周杰伦"))

    def test_a_failed_delete_does_not_trigger_a_rescan(self):
        """整批路径都不存在时曲库没动，不该白扫一遍。"""
        with patch.object(self.index, "refresh") as refresh:
            result = service.library_delete(
                service.LibraryPathsBody(paths=[str(self.library / "不存在.flac")])
            )

        self.assertEqual([], result["deleted"])
        refresh.assert_not_called()

    # 下面两条走真 HTTP：请求体字段名和响应形状是跟 App 的接口约定（工单 03），
    # 单测直接调函数是验不到的——字段拼错 App 就瞎了。

    def test_delete_over_http_answers_the_shape_the_app_parses(self):
        response = self._post("/api/library/delete", {"paths": [str(self.song)]})

        self.assertEqual(200, response.status_code)
        self.assertEqual({"deleted": ["周杰伦/叶惠美/03 晴天.flac"], "failed": []},
                         response.json())
        self.assertFalse(self.index.contains("晴天", "周杰伦"))

    def test_rename_over_http_writes_only_the_given_fields(self):
        response = self._post("/api/library/rename",
                              {"path": str(self.song), "title": "晴天 (Live)"})

        self.assertEqual(200, response.status_code)
        self.assertEqual({"ok": True, "path": "周杰伦/叶惠美/03 晴天.flac"}, response.json())
        self.assertTrue(self.index.contains("晴天 (Live)", "周杰伦"))


if __name__ == "__main__":
    unittest.main()
