# 02 — 服务端：HTTP 接口 + 文件操作密钥

**What to build:** 把 01 的模块挂到 `qqmusic_service.py` 上，并给这几个**危险接口**配一把锁（ADR-0001 的后果：服务监听 `0.0.0.0` 且原本没有任何鉴权）。

**Blocked by:** 01

**Status:** resolved

### 密钥

- [x] `server/.file_ops_key`：启动时不存在就用 `secrets.token_urlsafe(24)` 生成并写入；存在就直接读
- [x] 加进 `.gitignore`（**别提交上去**）
- [x] 启动日志打印密钥值 + 文件路径，方便用户抄到 App 里
- [x] FastAPI 依赖 `require_key`，用 `secrets.compare_digest` 比较（不要用 `==`）
- [x] **只挂在下面这几个路由上**。`/api/health`、`/api/search`、`/api/album/*`、`/api/download*`、`/api/jobs*`、`/api/credential/retry` **一律不挂**——密钥对不上时搜歌下载照常能用
- [x] 密钥错误返回 **401**，body 是中文说明（照现有 `HTTPException(503, "中文…")` 的风格）

### 接口

- [x] `POST /api/library/delete`，body `{paths: [str]}` → `{deleted: [...], failed: [{path, error}]}`
- [x] `POST /api/library/rename`，body `{path, title?, artist?, album?}` → `{ok, path}`
- [x] `GET  /api/library/recycle` → `{files: [{rel_path, size, deleted_at}], total_size}`
- [x] `POST /api/library/recycle/restore`，body `{paths: [str]}` → `{restored: [...], failed: [...]}`
- [x] `POST /api/library/recycle/purge` → `{purged: int}`

### 与曲库索引的联动

- [x] **每个危险操作成功后立刻调 `_library_index.refresh()`**。索引的设计假设原本是「曲库只增不改」（`library_index.py:37`），删/改打破了这个假设——不刷新的话，你删完一首歌想重下，会被自己的去重挡下来
- [x] `refresh()` 跳过没变的文件（按 mtime+size），所以删/改之后重扫只重读那一个文件，代价很低——**不要为这事新造接口**

### 测试

- [x] 在 `server/test_qqmusic_service.py` 里加：无密钥 → 401、错密钥 → 401、对密钥 → 通过
- [x] 加一条：**没挂密钥的接口（`/api/health`、`/api/search`）在无密钥时仍然正常**
- [x] 加一条：删完之后 `_library_index.contains()` 立刻变成 False

## Comments
