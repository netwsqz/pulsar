# 01 — 服务端：文件操作模块 `library_files.py`

**What to build:** 一个独立的 Python 模块，干四件事：删（挪进回收站）、改（写内嵌标签）、恢复、清空。照 `library_index.py` 的样子写——**不依赖 `qmc2`/`frida`**，这样测试跑得动。纯逻辑可单测，IO 那半不测。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 新文件 `server/library_files.py`，只依赖标准库 + `mutagen`（已装，不新增依赖）
- [ ] 常量 `RECYCLE_ROOT = Path("E:/file/Te_Music_deleted")`；曲库根复用 `library_index.DEFAULT_LIBRARY_ROOT`，不另抄一份
- [ ] **路径校验**（最关键的一条）：所有入参路径先 `resolve()`，再确认它真的在 `LIBRARY_ROOT` / `RECYCLE_ROOT` 之内，否则抛错拒绝。照 `qqmusic_service._parse_final_path:251` 的写法
- [ ] `delete(paths) -> DeleteResult`：把文件挪进回收站，**保持相对路径**，父目录不存在就建
- [ ] 挪完**向上清空目录**：从文件所在目录往上走，直到 `LIBRARY_ROOT` 为止；某目录里只剩 `.nfo` 时，把那些 `.nfo` 也挪进回收站再删掉目录；**只要目录里还剩任何一个非 `.nfo` 文件，整个目录原样不动**
- [ ] `rename(path, title=None, artist=None, album=None)`：写内嵌标签。**必须用 `mutagen` 的 `easy=True`**（跟 `library_index.read_tags:146` 一致；MP3 不加 easy 会写错帧名）。三个字段都可选，只写传了的，不传的不动
- [ ] 写标签后**立刻读回来校验**，对不上就报错——不许静默失败
- [ ] `restore(rel_paths)`：从回收站挪回原处，父目录不存在就建；**目标位置已有同名文件时拒绝**，绝不覆盖
- [ ] `list_recycle() -> list[{rel_path, size, deleted_at}]`
- [ ] `purge()`：真删回收站内容
- [ ] 单元测试 `server/test_library_files.py`，照 `test_library_index.py` 的样子：用 `tmp_path` 造假曲库 + 假回收站，**不碰真目录**
- [ ] 测试至少覆盖：路径越界被拒、挪进去相对路径对、恢复能挪回来、目标已存在时恢复被拒、只剩 `.nfo` 的目录被清掉、还有别的文件时目录不动、改标签读得回来
- [ ] 加一个 `--selftest` 命令行入口：在**真曲库**里造一个临时文件走完整流程（删 → 列出 → 恢复 → 再删 → 清空），跑完不留痕迹。这是唯一需要碰真目录的路径，供用户亲眼验证

## Comments
