"""现有 PC 伴生服务的只读曲库接口。"""

import logging
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path
from secrets import compare_digest, token_hex

import anyio
from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, StrictStr
from starlette.responses import FileResponse, JSONResponse, Response

from library_index import LibraryIndex, audio_revision, same_audio_file

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


class _VersionedAudioResponse(FileResponse):
    """条件读取先打开再核对，实际读取与校验用同一个文件句柄。"""

    async def __call__(self, scope, receive, send):
        try:
            file = await anyio.open_file(self.path, "rb")
        except OSError:
            return await Response(status_code=412)(scope, receive, send)
        async with file:
            opened = await anyio.to_thread.run_sync(os.fstat, file.wrapped.fileno())
            if not same_audio_file(self.stat_result, opened):
                return await Response(status_code=412)(scope, receive, send)
            self._audio = file
            await super().__call__(scope, receive, send)

    async def _handle_simple(self, send, send_header_only, send_pathsend):
        await send({"type": "http.response.start", "status": 200, "headers": self.raw_headers})
        await self._send_audio(send, 0 if send_header_only else self.stat_result.st_size)

    async def _handle_single_range(self, send, start, end, file_size, send_header_only):
        headers = self.headers.mutablecopy()
        headers["content-range"] = f"bytes {start}-{end - 1}/{file_size}"
        headers["content-length"] = str(end - start)
        await self._audio.seek(start)
        await send({"type": "http.response.start", "status": 206, "headers": headers.raw})
        await self._send_audio(send, 0 if send_header_only else end - start)

    async def _handle_multiple_ranges(self, send, ranges, file_size, send_header_only):
        # 手机续传每次只请求一段；普通播放的多段 Range 继续由 FileResponse 处理。
        await send({"type": "http.response.start", "status": 400, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    async def _send_audio(self, send, remaining):
        if not remaining:
            await send({"type": "http.response.body", "body": b""})
            return
        while remaining:
            chunk = await self._audio.read(min(remaining, self.chunk_size))
            if not chunk:
                raise OSError("读取期间文件长度发生变化")
            remaining -= len(chunk)
            if remaining == 0:
                # 最后一块不确认就不交付；连接提前结束，手机不会把这份副本标为完整。
                opened = await anyio.to_thread.run_sync(os.fstat, self._audio.wrapped.fileno())
                if not same_audio_file(self.stat_result, opened):
                    raise OSError("读取期间文件发生变化")
            await send({"type": "http.response.body", "body": chunk, "more_body": remaining > 0})


def _checked_revision(info: os.stat_result, if_match: str | None) -> str:
    tag = f'"{audio_revision(info)}"'
    if if_match is not None and not any(part.strip() in (tag, "*") for part in if_match.split(",")):
        raise HTTPException(412, "电脑上的文件已变化，请刷新曲库")
    return tag


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

    @router.get("/tracks/{track_id}/artwork", response_class=Response)
    def artwork(track_id: str, if_match: str | None = Header(None)) -> Response:
        source = index.audio_file(track_id)
        if source is None:
            raise HTTPException(404, "曲目不存在")
        path, info = source
        tag = _checked_revision(info, if_match)
        picture = index.read_artwork(path, info)
        if picture is None:
            raise HTTPException(404, "没有内嵌封面")
        data, mime = picture
        return Response(data, media_type=mime, headers={"Cache-Control": "private, no-store", "ETag": tag})

    @router.get("/tracks/{track_id}/audio", response_class=FileResponse)
    def audio(track_id: str, if_match: str | None = Header(None)) -> FileResponse:
        file = index.audio_file(track_id)
        if file is None:
            raise HTTPException(404, "曲目不存在")
        path, info = file
        # 手动下载补缺口时，旧版本的片段不能和新文件的字节拼在一起。
        tag = _checked_revision(info, if_match)
        response = FileResponse if if_match is None else _VersionedAudioResponse
        return response(path, stat_result=info, headers={"Cache-Control": "private, no-store", "ETag": tag})

    return router
