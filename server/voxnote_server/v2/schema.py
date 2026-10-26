"""v2 数据库 schema（第一版）。

原则：
  - 一条事实只存一处；records 是记录元数据与状态的唯一真相；
  - 转写是多版本"尝试"（transcript_attempts），人工修订单独成表（edits）；
  - 删除用墓碑（records.deleted_at），默认不动物理文件；
  - changes 是单调 rev 的变更流，所有写操作都要落一条；
  - 迁移策略是破坏性重建：本 schema 只保证空库初始化，不兼容 v1 数据。

表结构说明见 docs/设计/架构设计.md §3、§4。
"""
SCHEMA_VERSION = 1

SCHEMA_SQL = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_meta(
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS records(
  id                TEXT PRIMARY KEY,
  rev               INTEGER NOT NULL DEFAULT 0,
  legacy_id         TEXT,
  deleted_at        INTEGER,
  title             TEXT NOT NULL DEFAULT '',
  cat               TEXT,
  mode              TEXT NOT NULL DEFAULT '',
  src               TEXT NOT NULL DEFAULT 'app',
  device_id         TEXT NOT NULL DEFAULT '',
  marks             TEXT NOT NULL DEFAULT '[]',
  recorded_at       INTEGER NOT NULL,
  dur_ms            INTEGER NOT NULL DEFAULT 0,
  bytes             INTEGER NOT NULL DEFAULT 0,
  audio_path        TEXT,
  audio_name        TEXT NOT NULL DEFAULT '',
  audio_sha256      TEXT UNIQUE,
  status            TEXT NOT NULL DEFAULT 'local_only',
  engine            TEXT NOT NULL DEFAULT '',
  error_code        TEXT NOT NULL DEFAULT '',
  error_hint        TEXT NOT NULL DEFAULT '',
  active_attempt_id TEXT,
  active_pinned     INTEGER NOT NULL DEFAULT 0,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_records_rev      ON records(rev);
CREATE INDEX IF NOT EXISTS idx_records_created  ON records(recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_records_deleted  ON records(deleted_at);

CREATE TABLE IF NOT EXISTS transcript_attempts(
  id            TEXT PRIMARY KEY,
  record_id     TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  rev           INTEGER NOT NULL DEFAULT 0,
  audio_sha256  TEXT NOT NULL,
  source        TEXT NOT NULL,
  engine        TEXT NOT NULL,
  model         TEXT NOT NULL DEFAULT '',
  params_hash   TEXT NOT NULL DEFAULT '',
  quality_tier  INTEGER NOT NULL DEFAULT 2,
  cost_hint     TEXT NOT NULL DEFAULT '',
  duration_ms   INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  UNIQUE(record_id, audio_sha256, source, engine, params_hash)
);
CREATE INDEX IF NOT EXISTS idx_attempts_record ON transcript_attempts(record_id);

CREATE TABLE IF NOT EXISTS attempt_segments(
  attempt_id TEXT NOT NULL REFERENCES transcript_attempts(id) ON DELETE CASCADE,
  idx        INTEGER NOT NULL,
  t_ms       INTEGER NOT NULL DEFAULT 0,
  e_ms       INTEGER NOT NULL DEFAULT 0,
  text       TEXT NOT NULL DEFAULT '',
  spk        INTEGER NOT NULL DEFAULT -1,
  PRIMARY KEY(attempt_id, idx)
);

CREATE TABLE IF NOT EXISTS edits(
  id              TEXT PRIMARY KEY,
  record_id       TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  rev             INTEGER NOT NULL DEFAULT 0,
  anchor_t_ms     INTEGER NOT NULL DEFAULT 0,
  anchor_e_ms     INTEGER NOT NULL DEFAULT 0,
  original_hash   TEXT NOT NULL DEFAULT '',
  text            TEXT,
  spk             INTEGER,
  deleted         INTEGER NOT NULL DEFAULT 0,
  author          TEXT NOT NULL DEFAULT '',
  base_attempt_id TEXT,
  edited_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_edits_record ON edits(record_id, anchor_t_ms);

CREATE TABLE IF NOT EXISTS jobs(
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  record_id        TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  engine           TEXT NOT NULL DEFAULT '',
  source           TEXT NOT NULL DEFAULT 'hub',
  state            TEXT NOT NULL DEFAULT 'queued',
  stage            TEXT NOT NULL DEFAULT '',
  progress         REAL NOT NULL DEFAULT 0,
  error_code       TEXT NOT NULL DEFAULT '',
  error_hint       TEXT NOT NULL DEFAULT '',
  priority         INTEGER NOT NULL DEFAULT 0,
  cancel_requested INTEGER NOT NULL DEFAULT 0,
  queued_at        INTEGER,
  started_at       INTEGER,
  finished_at      INTEGER
);
CREATE INDEX IF NOT EXISTS idx_jobs_state ON jobs(state, id);
CREATE INDEX IF NOT EXISTS idx_jobs_record ON jobs(record_id, id);

CREATE TABLE IF NOT EXISTS devices(
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL DEFAULT '',
  kind         TEXT NOT NULL DEFAULT 'phone',
  app_version  TEXT NOT NULL DEFAULT '',
  last_seen_at INTEGER,
  last_cursor  INTEGER NOT NULL DEFAULT 0,
  status       TEXT NOT NULL DEFAULT 'active',
  created_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS engines(
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL DEFAULT 'openai',
  name         TEXT NOT NULL DEFAULT '',
  url          TEXT NOT NULL DEFAULT '',
  token        TEXT NOT NULL DEFAULT '',
  model        TEXT NOT NULL DEFAULT '',
  quality_tier INTEGER NOT NULL DEFAULT 3,
  slots        INTEGER NOT NULL DEFAULT 1,
  timeout_ms   INTEGER NOT NULL DEFAULT 600000,
  enabled      INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS conflicts(
  id                TEXT PRIMARY KEY,
  record_id         TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  kind              TEXT NOT NULL,
  base_rev          INTEGER NOT NULL DEFAULT 0,
  local_payload     TEXT NOT NULL DEFAULT '{}',
  server_payload    TEXT NOT NULL DEFAULT '{}',
  created_at        INTEGER NOT NULL,
  resolved_at       INTEGER,
  resolution        TEXT
);
CREATE INDEX IF NOT EXISTS idx_conflicts_open ON conflicts(record_id, resolved_at);

CREATE TABLE IF NOT EXISTS changes(
  rev        INTEGER PRIMARY KEY AUTOINCREMENT,
  kind       TEXT NOT NULL,
  entity_id  TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_changes_entity ON changes(kind, entity_id);

CREATE TABLE IF NOT EXISTS settings(
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL DEFAULT ''
);
"""
