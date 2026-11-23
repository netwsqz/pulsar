"""电脑曲库 HTTP 合同：只用临时曲库和访问密钥，不加载 QQ 音乐导入服务。"""

import os
import tempfile
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient
from mutagen.flac import FLAC

from library_index import LibraryIndex
from pc_library import library_router
from test_library_files import minimal_flac


class PcLibraryHttpTest(unittest.TestCase):
    TRACKS = "/api/library/tracks"
    KEY = "a" * 64
    AUTH = {"Authorization": f"Bearer {KEY}"}

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.sandbox = Path(directory.name).resolve()
        self.root = self.sandbox / "曲库目录" / "library"
        self.root.mkdir(parents=True)
        self.key_file = self.sandbox / ".library_access_key"
        self.key_file.write_text(self.KEY, encoding="ascii")
        self.index = LibraryIndex(self.root)

    def app(self):
        app = FastAPI()
        app.include_router(library_router(self.index, self.key_file))
        return app

    def client(self):
        return TestClient(self.app())

    def test_both_routes_require_access_key_before_checking_index(self):
        with self.client() as client:
            for url in (self.TRACKS, f"{self.TRACKS}/{'0' * 64}/audio"):
                with self.subTest(url=url):
                    for headers in ({}, {"Authorization": "Bearer wrong"},
                                    {"X-Pulsar-Key": self.KEY}, {"Authorization": f"Basic {self.KEY}"}):
                        denied = client.get(url, headers=headers)
                        self.assertEqual(401, denied.status_code)
                        self.assertEqual("Bearer", denied.headers.get("WWW-Authenticate"))
                    self.assertEqual(503, client.get(url, headers=self.AUTH).status_code)

    def make_track(self, name, **tags):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(minimal_flac())
        if tags:
            self.write_tags(path, **tags)
        return path

    @staticmethod
    def write_tags(path, **tags):
        audio = FLAC(path)
        for name, value in tags.items():
            audio[name] = [value]
        audio.save()

    def test_authorized_list_uses_embedded_tags_without_exposing_file_paths(self):
        self.make_track("临时文件夹/01 不是标签歌名.flac", title="歌名？", artist="歌手", album="专辑")
        self.make_track("歌手二 - 另一首_H.flac")
        (self.root / "album.nfo").write_text("说明", encoding="utf-8")
        self.index.refresh()
        with self.client() as client:
            response = client.get(self.TRACKS, headers=self.AUTH)
        self.assertEqual(200, response.status_code)
        tracks = response.json()["tracks"]
        self.assertCountEqual(
            [("歌名？", "歌手", "专辑"), ("另一首", "歌手二", "")],
            [(t["title"], t["artist"], t["album"]) for t in tracks],
        )
        for track in tracks:
            self.assertEqual({"id", "title", "artist", "album"}, set(track))
            self.assertRegex(track["id"], r"^[0-9a-f]{64}$")
        self.assertEqual(len(tracks), len({track["id"] for track in tracks}))
        for private in (str(self.root), "临时文件夹", ".flac", self.KEY):
            self.assertNotIn(private, response.text)

    def test_authorized_audio_returns_original_bytes_and_supports_seeking(self):
        track = self.make_track("歌手/专辑/01 文件.flac", title="原声")
        source = track.read_bytes()
        self.index.refresh()
        with self.client() as client:
            track_id = client.get(self.TRACKS, headers=self.AUTH).json()["tracks"][0]["id"]
            url = f"{self.TRACKS}/{track_id}/audio"
            denied = client.get(url, headers={"Range": "bytes=4-11"})
            full = client.get(url, headers=self.AUTH)
            part = client.get(url, headers={**self.AUTH, "Range": "bytes=4-11"})
            rest = client.get(url, headers={**self.AUTH, "Range": "bytes=12-"})
            unsatisfiable = client.get(url, headers={**self.AUTH, "Range": f"bytes={len(source)}-"})
        self.assertEqual(401, denied.status_code)
        self.assertEqual(200, full.status_code)
        self.assertEqual(source, full.content)
        self.assertEqual("bytes", full.headers.get("Accept-Ranges"))
        self.assertNotIn("Content-Disposition", full.headers)
        self.assertEqual(206, part.status_code)
        self.assertEqual(f"bytes 4-11/{len(source)}", part.headers["Content-Range"])
        self.assertEqual(source[4:12], part.content)
        self.assertEqual(206, rest.status_code)
        self.assertEqual(source[12:], rest.content)
        self.assertEqual(416, unsatisfiable.status_code)
        self.assertTrue(unsatisfiable.headers["Content-Range"].endswith(f"*/{len(source)}"))
        self.assertEqual(source, track.read_bytes())

    def test_access_key_is_created_once_and_still_works_on_next_start(self):
        self.key_file.unlink()
        self.index.refresh()
        with self.client() as first:
            self.assertTrue(self.key_file.is_file())
            key = self.key_file.read_text(encoding="ascii").strip()
            self.assertRegex(key, r"^[0-9a-f]{64}$")
            auth = {"Authorization": f"Bearer {key}"}
            self.assertEqual(200, first.get(self.TRACKS, headers=auth).status_code)
        with self.client() as restarted:
            self.assertEqual(200, restarted.get(self.TRACKS, headers=auth).status_code)
        self.assertEqual(key, self.key_file.read_text(encoding="ascii").strip())

    def test_access_key_write_failure_disables_only_the_pc_library(self):
        self.key_file = self.key_file.parent / "不存在的目录" / self.key_file.name
        self.index.refresh()
        app = self.app()

        @app.get("/api/health")
        def health():
            return {"ok": True}

        with self.assertLogs("pc_library", level="WARNING") as messages, TestClient(app) as client:
            self.assertEqual({"ok": True}, client.get("/api/health").json())
            for url in (self.TRACKS, f"{self.TRACKS}/missing/audio"):
                response = client.get(url, headers=self.AUTH)
                self.assertEqual(500, response.status_code)
                self.assertNotIn(self.key_file.name, response.text)
                self.assertNotIn(self.KEY, response.text)
        self.assertIn(self.key_file.name, "\n".join(messages.output))
        self.assertNotIn(self.KEY, "\n".join(messages.output))

    def test_existing_invalid_access_key_is_not_overwritten_or_accepted(self):
        invalid_key = b"not a generated access key"
        self.key_file.write_bytes(invalid_key)
        self.index.refresh()
        with self.assertLogs("pc_library", level="WARNING") as messages, self.client() as client:
            response = client.get(self.TRACKS, headers={"Authorization": b"Bearer " + invalid_key})
        self.assertEqual(500, response.status_code)
        self.assertEqual(invalid_key, self.key_file.read_bytes())
        self.assertNotIn(invalid_key.decode("ascii"), "\n".join(messages.output))

    def test_missing_library_root_reports_not_ready_instead_of_pairing_to_an_empty_library(self):
        self.root.rmdir()
        self.index.refresh()
        with self.client() as client:
            self.assertEqual(503, client.get(self.TRACKS, headers=self.AUTH).status_code)
            self.assertEqual(503, client.get(f"{self.TRACKS}/missing/audio", headers=self.AUTH).status_code)
            self.root.mkdir()
            self.index.refresh()
            response = client.get(self.TRACKS, headers=self.AUTH)
            self.assertEqual(200, response.status_code)
            self.assertEqual({"tracks": []}, response.json())

    def test_id_survives_tag_edit_rescan_and_service_restart(self):
        track = self.make_track("专辑/01 歌曲.flac", title="原曲")
        os.utime(track, (1_700_000_000, 1_700_000_000))
        self.index.refresh()
        with self.client() as client:
            original = client.get(self.TRACKS, headers=self.AUTH).json()["tracks"][0]
            self.write_tags(track, title="改过的歌名", album="新专辑")
            self.index.refresh()
            edited = client.get(self.TRACKS, headers=self.AUTH).json()["tracks"][0]
        self.assertEqual("改过的歌名", edited["title"])
        self.assertEqual("新专辑", edited["album"])
        self.assertEqual(original["id"], edited["id"])
        self.index = LibraryIndex(self.root)
        self.index.refresh()
        with self.client() as client:
            self.assertEqual([edited], client.get(self.TRACKS, headers=self.AUTH).json()["tracks"])

    def test_unknown_or_stale_id_does_not_play(self):
        track = self.make_track("专辑/01 歌曲.flac")
        self.index.refresh()
        with self.client() as client:
            response = client.get(self.TRACKS, headers=self.AUTH)
            track_id = response.json()["tracks"][0]["id"]
            url = f"{self.TRACKS}/{track_id}/audio"
            self.assertEqual(200, client.get(url, headers=self.AUTH).status_code)
            unknown = f"{self.TRACKS}/{'0' * 64}/audio"
            self.assertEqual(404, client.get(unknown, headers=self.AUTH).status_code)
            track.unlink()
            self.assertEqual(404, client.get(url, headers=self.AUTH).status_code)

    def test_links_outside_the_library_are_not_indexed_or_played(self):
        outside = self.sandbox / "曲库目录-外部"
        outside.mkdir()
        (outside / "01 歌曲.flac").write_bytes(minimal_flac())
        track = self.make_track("专辑/01 歌曲.flac")
        self.index.refresh()
        with self.client() as client:
            track_id = client.get(self.TRACKS, headers=self.AUTH).json()["tracks"][0]["id"]
            track.unlink()
            track.parent.rmdir()
            if os.name == "nt":
                from _winapi import CreateJunction
                CreateJunction(str(outside), str(track.parent))
            else:
                track.parent.symlink_to(outside, target_is_directory=True)
            url = f"{self.TRACKS}/{track_id}/audio"
            self.assertEqual(404, client.get(url, headers=self.AUTH).status_code)
            self.index.refresh()
            self.assertEqual({"tracks": []}, client.get(self.TRACKS, headers=self.AUTH).json())


if __name__ == "__main__":
    unittest.main()
