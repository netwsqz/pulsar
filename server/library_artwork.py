"""从音频内嵌标签提取可显示的封面；不读外部图片或标签里的链接。"""

import base64
from collections.abc import Iterable

from mutagen.apev2 import APEv2
from mutagen.asf import ASFTags
from mutagen.flac import Picture
from mutagen.id3 import ID3
from mutagen.mp4 import MP4Tags

MAX_ARTWORK_BYTES = 10 * 1024 * 1024


def embedded_artwork(audio) -> tuple[bytes, str] | None:
    if audio is None:
        return None
    pictures = _pictures(audio)
    # 先找封面正面，其他内嵌图片只作兜底。
    for _, data in sorted(pictures, key=lambda item: item[0] != 3):
        mime = _image_type(data)
        if mime is not None:
            return data, mime
    return None


def _pictures(audio) -> Iterable[tuple[int, bytes]]:
    for picture in getattr(audio, "pictures", ()):
        yield picture.type, picture.data
    tags = audio.tags
    if isinstance(tags, ID3):
        for picture in tags.getall("APIC"):
            yield picture.type, picture.data
    elif isinstance(tags, MP4Tags):
        for picture in tags.get("covr", []):
            yield 3, bytes(picture)
    elif isinstance(tags, APEv2):
        for key, kind in (("Cover Art (Front)", 3), ("Cover Art (Back)", 4)):
            value = tags.get(key)
            if value is not None and isinstance(value.value, bytes):
                _, separator, image = value.value.partition(b"\x00")
                if separator:
                    yield kind, image
    elif isinstance(tags, ASFTags):
        for picture in tags.get("WM/Picture", []):
            value = picture.value
            if not isinstance(value, bytes) or len(value) < 5:
                continue
            length = int.from_bytes(value[1:5], "little")
            # 头部后是两个以 UTF-16 NUL 结尾的字符串，再接图片字节。
            offset = len(value) - length
            if offset >= 9 and offset % 2 == 1 and value[offset - 2:offset] == b"\x00\x00":
                yield value[0], value[offset:]
    elif tags:
        for encoded in tags.get("metadata_block_picture", []):
            if len(encoded) > (MAX_ARTWORK_BYTES + 64 * 1024) * 4 // 3:
                continue
            try:
                picture = Picture(base64.b64decode(encoded, validate=True))
            except (ValueError, TypeError):
                continue
            yield picture.type, picture.data


def _image_type(data: bytes) -> str | None:
    if len(data) > MAX_ARTWORK_BYTES:
        return None
    # 不把标签自报的 MIME 原样交给 HTTP 客户端。
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    if data.startswith(b"BM"):
        return "image/bmp"
    return None
