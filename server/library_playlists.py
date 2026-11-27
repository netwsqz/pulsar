"""电脑曲库自己的播放列表：只存名称和有序曲目 ID，不读写音频或旧列表。"""

import json
import os
import re
from pathlib import Path
from tempfile import NamedTemporaryFile
from threading import Lock
from typing import Callable
from uuid import uuid4

# 沿用伴生服务兼容的 Pydantic 1 / 2 接口。

from pydantic import BaseModel, StrictStr, validator


def _validate_order(order: list[str]) -> list[str]:
    if len(order) > 10000 or any(not re.fullmatch(r"[0-9a-f]{64}", track_id) for track_id in order):
        raise ValueError("曲目 ID 或列表长度不正确")
    if len(order) != len(set(order)):
        raise ValueError("播放列表不能有重复曲目")
    return order


class NewPlaylist(BaseModel):
    name: StrictStr

    @validator("name")
    def valid_name(cls, name: str) -> str:
        name = name.strip()
        if not 1 <= len(name) <= 120:
            raise ValueError("播放列表名称须为 1 到 120 个字符")
        return name


class PlaylistOrder(BaseModel):
    track_ids: list[StrictStr]

    _check_order = validator("track_ids", allow_reuse=True)(_validate_order)


class PlaylistEdit(PlaylistOrder):
    previous_track_ids: list[StrictStr]

    _check_previous = validator("previous_track_ids", allow_reuse=True)(_validate_order)


class LibraryPlaylist(NewPlaylist, PlaylistOrder):
    id: StrictStr

    @validator("id")
    def valid_id(cls, playlist_id: str) -> str:
        if not re.fullmatch(r"[0-9a-f]{32}", playlist_id):
            raise ValueError("播放列表 ID 不正确")
        return playlist_id


class InvalidPlaylistTracks(ValueError):
    pass


class PlaylistEditConflict(ValueError):
    pass


class LibraryPlaylists:
    def __init__(self, path: Path):
        self._path = path
        self._lock = Lock()

    def all(self) -> list[LibraryPlaylist]:
        with self._lock:
            return self._read()

    def create(self, name: str) -> LibraryPlaylist:
        with self._lock:
            playlists = self._read()
            playlist = LibraryPlaylist(id=uuid4().hex, name=name, track_ids=[])
            self._write([*playlists, playlist])
            return playlist

    def replace_order(self, playlist_id: str, edit: PlaylistEdit, contains: Callable[[str], bool]) -> LibraryPlaylist:
        with self._lock:
            playlists = self._read()
            for i, playlist in enumerate(playlists):
                if playlist.id != playlist_id:
                    continue
                order = edit.track_ids
                if order == playlist.track_ids:
                    return playlist  # 允许手机在响应丢失后重试相同的保存。
                if playlist.track_ids != edit.previous_track_ids:
                    raise PlaylistEditConflict
                saved = set(playlist.track_ids)
                # 暂时移走的曲目可以留在原列表，但不能向列表加入不存在的曲目。
                if any(track_id not in saved and not contains(track_id) for track_id in order):
                    raise InvalidPlaylistTracks
                updated = LibraryPlaylist(id=playlist.id, name=playlist.name, track_ids=order)
                playlists[i] = updated
                self._write(playlists)
                return updated
            raise KeyError(playlist_id)

    def _read(self) -> list[LibraryPlaylist]:
        try:
            raw = self._path.read_bytes()
        except FileNotFoundError:
            return []
        rows = json.loads(raw)
        if not isinstance(rows, list):
            raise ValueError("播放列表文件不是列表")
        playlists = [LibraryPlaylist(**row) for row in rows]
        if len({playlist.id for playlist in playlists}) != len(playlists):
            raise ValueError("播放列表文件包含重复 ID")
        return playlists

    def _write(self, playlists: list[LibraryPlaylist]) -> None:
        temporary = None
        try:
            with NamedTemporaryFile(mode="w", encoding="utf-8", dir=self._path.parent,
                                    prefix=self._path.name + ".", suffix=".tmp", delete=False) as file:
                temporary = Path(file.name)
                rows = [{"id": item.id, "name": item.name, "track_ids": item.track_ids} for item in playlists]
                json.dump(rows, file, ensure_ascii=False)
                file.flush()
                os.fsync(file.fileno())
            os.replace(temporary, self._path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
