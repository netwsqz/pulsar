"""电脑曲库的播放历史；不读写音频、播放列表或 Jellyfin 数据。"""

import re
import sqlite3
from contextlib import closing, contextmanager
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, StrictStr, conint, validator


class PlaybackEvent(BaseModel):
    event_id: StrictStr
    event: Literal["start", "stop"]
    position_ticks: conint(strict=True, ge=0, le=2**63 - 1)

    @validator("event_id")
    def valid_id(cls, value: str) -> str:
        if not re.fullmatch(r"[0-9a-f]{32}", value):
            raise ValueError("播放事件 ID 不正确")
        return value


class LibraryHistory:
    def __init__(self, path: Path):
        self._path = path

    @contextmanager
    def _connection(self):
        with closing(sqlite3.connect(self._path)) as db, db:
            db.execute("""CREATE TABLE IF NOT EXISTS plays (
                sequence INTEGER PRIMARY KEY,
                event_id TEXT NOT NULL UNIQUE,
                track_id TEXT NOT NULL,
                start_ticks INTEGER NOT NULL,
                stop_ticks INTEGER
            )""")
            yield db

    def record(self, track_id: str, report: PlaybackEvent) -> None:
        with self._connection() as db:
            if report.event == "start":
                db.execute("""INSERT INTO plays (event_id, track_id, start_ticks) VALUES (?, ?, ?)
                    ON CONFLICT(event_id) DO NOTHING""", (report.event_id, track_id, report.position_ticks))
            else:
                # 仅更新同一次播放，不把迟到或重复的停止当成新的收听。
                db.execute("""UPDATE plays SET stop_ticks = ?
                    WHERE event_id = ? AND track_id = ? AND stop_ticks IS NULL""",
                    (report.position_ticks, report.event_id, track_id))

    def ranked_ids(self, *, by_frequency: bool) -> list[str]:
        with self._connection() as db:
            order = "COUNT(*) DESC, MAX(sequence) DESC" if by_frequency else "MAX(sequence) DESC"
            return [track_id for track_id, in db.execute(
                f"SELECT track_id FROM plays GROUP BY track_id ORDER BY {order}"
            )]
