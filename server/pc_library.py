"""现有 PC 伴生服务的只读曲库接口。"""

import logging
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path
from secrets import compare_digest, token_hex

from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, StrictStr
from starlette.responses import FileResponse, JSONResponse

from library_index import LibraryIndex

log = logging.getLogger(__name__)


def _load_access_key(path: Path) -> bytes:
    try:
        key = path.read_bytes().strip()
    except FileNotFoundError:
        key = token_hex(32).encode("ascii")
        # 排他创建：即使有另一个服务进程先写，也绝不覆盖它的密钥。
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            key = path.read_bytes().strip()
        else:
            with os.fdopen(descriptor, "wb") as file:
                file.write(key + b"\n")
    if not re.fullmatch(rb"[0-9a-f]{64}", key):
        raise ValueError("访问密钥文件格式不正确")
    return key


class EvidenceRequest(BaseModel):
    paths: list[StrictStr]


def library_router(index: LibraryIndex, key_file: Path) -> APIRouter:
    access_key: bytes | None = None

    @asynccontextmanager
    async def lifespan(_app):
        nonlocal access_key
        try:
            access_key = _load_access_key(key_file)
        except (OSError, ValueError):
            access_key = None
            log.warning("电脑曲库未启用：无法读取或生成访问密钥文件 %s", key_file)
        else:
            log.info("电脑曲库访问密钥文件: %s（配对时在电脑上打开此文件）", key_file)
        try:
            yield
        finally:
            access_key = None

    bearer = HTTPBearer(auto_error=False)

    def require_access(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)) -> None:
        if access_key is None:
            raise HTTPException(500, "电脑曲库暂不可用")
        if credentials is None or not compare_digest(credentials.credentials.encode("utf-8"), access_key):
            raise HTTPException(401, "访问密钥不正确", headers={"WWW-Authenticate": "Bearer"})
        if not index.catalog_ready:
            raise HTTPException(503, "曲库索引尚未就绪")

    router = APIRouter(prefix="/api/library", dependencies=[Depends(require_access)], lifespan=lifespan)

    @router.get("/tracks")
    def tracks() -> dict:
        return {"tracks": index.catalog()}

    @router.post("/evidence")
    def evidence(request: EvidenceRequest) -> JSONResponse:
        if len(request.paths) > 2000 or any(len(path) > 4096 for path in request.paths):
            raise HTTPException(413, "本次核对的路径过多或过长")
        return JSONResponse({"tracks": index.evidence(request.paths)}, headers={"Cache-Control": "private, no-store"})

    @router.get("/tracks/{track_id}/audio", response_class=FileResponse)
    def audio(track_id: str) -> FileResponse:
        file = index.audio_file(track_id)
        if file is None:
            raise HTTPException(404, "曲目不存在")
        path, info = file
        return FileResponse(path, stat_result=info, headers={"Cache-Control": "private, no-store"})

    return router
