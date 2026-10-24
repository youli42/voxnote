"""SQLite 存储层：设置、记录、逐句（机器层与人工修订分离）、任务、FTS 索引。

约定（对应 docs/DATA-CONTRACT.md 的人机分离原则）：
  - segments.m_text / m_spk 是机器转写层，重转写整层替换；
  - e_text / e_spk 是人工修订层，永远不被机器层覆盖；
  - 生效内容 = COALESCE(e_*, m_*)。

连接策略：sqlite3 文件库，WAL；每请求短连接（本服务写入频率低，够用且无线程坑）。
"""
import json
import os
import secrets
import sqlite3
import threading
import time

from . import config

_local = threading.local()


def conn() -> sqlite3.Connection:
    """每线程一个连接；FastAPI 线程池复用线程，实际连接数有限。"""
    c = getattr(_local, "conn", None)
    if c is None:
        config.ensure_dirs()
        c = sqlite3.connect(config.db_path(), timeout=15)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA foreign_keys=ON")
        _local.conn = c
    return c


SCHEMA = """
CREATE TABLE IF NOT EXISTS settings(
  k TEXT PRIMARY KEY, v TEXT);

CREATE TABLE IF NOT EXISTS records(
  id TEXT PRIMARY KEY,             -- yyyyMMdd_HHmmss（与 App 同键；冲突时加后缀）
  sid INTEGER NOT NULL DEFAULT 0,  -- 厂商会话号（可无）
  title TEXT NOT NULL DEFAULT '',
  cat TEXT,                        -- 分类 id（NULL=未分类）
  mode TEXT NOT NULL DEFAULT '',   -- meeting / memo / ...
  src TEXT NOT NULL DEFAULT 'app', -- 来源标记：app / upload / import
  dur_ms INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'raw',  -- raw|queued|asr|done|fail
  engine TEXT NOT NULL DEFAULT '',
  error TEXT NOT NULL DEFAULT '',          -- 人类可读的失败原因（归类后）
  error_detail TEXT NOT NULL DEFAULT '',   -- 原始异常串（控制台展开详情用）
  audio_path TEXT,
  audio_name TEXT NOT NULL DEFAULT '',
  bytes INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,     -- 录音时间（优先来自元数据）
  updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_records_created ON records(created_at DESC);

CREATE TABLE IF NOT EXISTS segments(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL DEFAULT 0,  -- 机器层序号（重转写后重新编号）
  t_ms INTEGER NOT NULL DEFAULT 0,
  e_ms INTEGER NOT NULL DEFAULT 0,
  m_text TEXT NOT NULL DEFAULT '',
  m_spk INTEGER NOT NULL DEFAULT -1,
  e_text TEXT,
  e_spk INTEGER,
  edited_at INTEGER);
CREATE INDEX IF NOT EXISTS idx_segs_record ON segments(record_id, t_ms);

CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(
  text, seg_id UNINDEXED, record_id UNINDEXED);

CREATE TABLE IF NOT EXISTS jobs(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  engine TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'queued',  -- queued|running|done|fail|cancel
  progress REAL NOT NULL DEFAULT 0,
  error TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  started_at INTEGER,
  finished_at INTEGER);
CREATE INDEX IF NOT EXISTS idx_jobs_state ON jobs(state, id);
"""

# 控制台可读写、首次启动给默认值的设置项（其余键只读展示）
SETTING_DEFAULTS = {
    "sync_token": "",            # 首次启动自动生成
    "default_engine": "whisper-local",
    "auto_transcribe": "1",      # 同步/导入后自动入队
    "whisper_model": "small",    # tiny/base/small/medium/large-v3
    "whisper_device": "auto",    # auto|cpu|cuda
    "whisper_compute": "auto",   # auto|int8|float16
    "whisper_language": "zh",    # auto|zh|en|ja|…（auto 会先检测语种，短音频易判错）
    "cloud_asr_base": "",        # OpenAI 兼容 /audio/transcriptions 地址
    "cloud_asr_key": "",
    "cloud_asr_model": "",
    "ai_base": "https://open.bigmodel.cn/api/paas/v4",
    "ai_key": "",
    "ai_model": "glm-4-flash",
    "llama_server_path": "",     # llama.cpp 发布包中的 llama-server(.exe) 路径
    "llama_port": "8849",
    "llama_model_dir": "",       # 留空 = data/models/qwen3-asr
    "llama_repo": "foryoung365/Qwen3-ASR-1.7B-Q4_K_M-GGUF",  # 下载仓库（q4_k，2C/6G 推荐）
    "hf_mirror": "https://hf-mirror.com",  # HuggingFace 镜像（直连不可达时用）
    "retention_audio": "keep",   # keep|compact（预留）
}

PUBLIC_SETTING_KEYS = [
    "default_engine", "auto_transcribe", "whisper_model", "whisper_device",
    "whisper_compute", "whisper_language", "cloud_asr_base", "cloud_asr_model",
    "ai_base", "ai_model", "llama_server_path", "llama_port", "llama_repo",
    "hf_mirror",
]


def init():
    with conn() as c:
        c.executescript(SCHEMA)
        _migrate(c)
        for k, v in SETTING_DEFAULTS.items():
            if get(k) is None:
                set(k, v)
        if not get("sync_token"):
            set("sync_token", "vn-" + secrets.token_hex(8))


def _migrate(c):
    """轻量迁移：老库补列，幂等。"""
    cols = {r["name"] for r in c.execute("PRAGMA table_info(records)").fetchall()}
    if "error_detail" not in cols:
        c.execute("ALTER TABLE records ADD COLUMN error_detail TEXT NOT NULL DEFAULT ''")


# ------------------------------------------------------------ 设置

def get(k: str, default=None):
    r = conn().execute("SELECT v FROM settings WHERE k=?", (k,)).fetchone()
    return r["v"] if r is not None else default


def set(k: str, v):
    with conn() as c:
        c.execute("INSERT INTO settings(k,v) VALUES(?,?) "
                  "ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, str(v)))


def check_token(given: str) -> bool:
    want = get("sync_token") or ""
    return bool(want) and secrets.compare_digest(want, given or "")


# ------------------------------------------------------------ 记录

def record_row(id_: str):
    return conn().execute("SELECT * FROM records WHERE id=?", (id_,)).fetchone()


def insert_record(**kw) -> sqlite3.Row:
    now = int(time.time() * 1000)
    kw.setdefault("created_at", now)
    kw["updated_at"] = now
    cols = ",".join(kw.keys())
    qs = ",".join("?" * len(kw))
    with conn() as c:
        c.execute("INSERT INTO records(%s) VALUES(%s)" % (cols, qs), tuple(kw.values()))
    return record_row(kw["id"])


def update_record(id_: str, **kw):
    kw["updated_at"] = int(time.time() * 1000)
    sets = ",".join("%s=?" % k for k in kw)
    with conn() as c:
        c.execute("UPDATE records SET %s WHERE id=?" % sets, tuple(kw.values()) + (id_,))


def unique_record_id(base: str) -> str:
    """同秒上传冲突时加 -2/-3 后缀，不覆盖已有记录。"""
    rid = base
    n = 2
    while record_row(rid) is not None:
        rid = "%s-%d" % (base, n)
        n += 1
    return rid


# ------------------------------------------------------------ 逐句（生效视图 + 双层写入）

def effective_segments(record_id: str):
    """生效内容（修订优先），供 API/导出/播放定位使用。"""
    return conn().execute(
        "SELECT id, idx, t_ms, e_ms,"
        " COALESCE(e_text, m_text) AS text,"
        " COALESCE(e_spk, m_spk) AS spk,"
        " e_text IS NOT NULL AS edited"
        " FROM segments WHERE record_id=? ORDER BY t_ms, idx", (record_id,)).fetchall()


def replace_machine_segments(record_id: str, segs):
    """重转写落地：只替换机器层；带修订的旧句保留（人工层不动）。

    segs: [{t_ms, e_ms, text, spk}]
    """
    now = int(time.time() * 1000)
    with conn() as c:
        old = c.execute(
            "SELECT id, idx, t_ms, e_ms, m_text, m_spk, e_text, e_spk, edited_at FROM segments"
            " WHERE record_id=? ORDER BY t_ms", (record_id,)).fetchall()
        c.execute("DELETE FROM segments WHERE record_id=?", (record_id,))
        c.execute("DELETE FROM fts WHERE record_id=?", (record_id,))
        # 修订过的旧句：作为"钉住的"修订行保留（时间轴取旧值，正文取修订值）
        for o in old:
            if o["e_text"] is not None or o["e_spk"] is not None:
                c.execute(
                    "INSERT INTO segments(record_id,idx,t_ms,e_ms,m_text,m_spk,e_text,e_spk,edited_at)"
                    " VALUES(?,?,?,?,?,?,?,?,?)",
                    (record_id, o["idx"], o["t_ms"], o["e_ms"], o["m_text"], o["m_spk"],
                     o["e_text"], o["e_spk"], o["edited_at"] or now))
                _fts_upsert(c, o["id"], record_id,
                            o["e_text"] if o["e_text"] is not None else o["m_text"])
        # 新机器层
        for i, s in enumerate(segs):
            cur = c.execute(
                "INSERT INTO segments(record_id,idx,t_ms,e_ms,m_text,m_spk)"
                " VALUES(?,?,?,?,?,?)",
                (record_id, i, s.get("t_ms", 0), s.get("e_ms", 0),
                 s.get("text", ""), s.get("spk", -1)))
            _fts_upsert(c, cur.lastrowid, record_id, s.get("text", ""))


def patch_segment(record_id: str, seg_id: int, text=None, spk=None) -> bool:
    """人工修订：只写 e_*，永不碰机器层。"""
    now = int(time.time() * 1000)
    with conn() as c:
        row = c.execute("SELECT * FROM segments WHERE id=? AND record_id=?",
                        (seg_id, record_id)).fetchone()
        if row is None:
            return False
        if text is not None:
            c.execute("UPDATE segments SET e_text=?, edited_at=? WHERE id=?", (text, now, seg_id))
        if spk is not None:
            c.execute("UPDATE segments SET e_spk=?, edited_at=? WHERE id=?", (spk, now, seg_id))
        eff = text if text is not None else (row["e_text"] if row["e_text"] is not None else row["m_text"])
        c.execute("DELETE FROM fts WHERE seg_id=?", (seg_id,))
        _fts_upsert(c, seg_id, record_id, eff or "")
    return True


def delete_segment(record_id: str, seg_id: int) -> bool:
    with conn() as c:
        cur = c.execute("DELETE FROM segments WHERE id=? AND record_id=?", (seg_id, record_id))
        c.execute("DELETE FROM fts WHERE seg_id=?", (seg_id,))
        return cur.rowcount > 0


def _fts_upsert(c, seg_id, record_id, text):
    c.execute("INSERT INTO fts(text, seg_id, record_id) VALUES(?,?,?)",
              (text or "", seg_id, record_id))


def first_texts(record_ids):
    """列表预览：每场第一条生效句，返回 {record_id: text}（无转写的记录不在结果里）。"""
    if not record_ids:
        return {}
    qs = ",".join("?" * len(record_ids))
    rows = conn().execute(
        "SELECT record_id, text FROM ("
        " SELECT record_id, COALESCE(e_text, m_text) AS text,"
        " ROW_NUMBER() OVER (PARTITION BY record_id ORDER BY t_ms, idx) AS rn"
        " FROM segments WHERE record_id IN (%s))"
        " WHERE rn = 1" % qs, tuple(record_ids)).fetchall()
    return {r["record_id"]: r["text"] for r in rows}


def distinct_cats():
    """库内出现过的自定义标签（安卓端可自建标签），带计数。"""
    return conn().execute(
        "SELECT cat, COUNT(*) AS n FROM records WHERE cat IS NOT NULL AND cat != ''"
        " GROUP BY cat ORDER BY n DESC, cat").fetchall()


def search_sentences(query: str, limit=12, record_ids=None):
    """句级召回（FTS5，前缀友好的 OR 查询），供 AI 引用与全局搜索。"""
    terms = [t for t in _tokenize(query) if len(t) >= 1]
    if not terms:
        return []
    match = " OR ".join('"%s"' % t.replace('"', '""') for t in terms[:8])
    sql = ("SELECT f.seg_id, f.record_id, f.text, r.title, r.created_at"
           " FROM fts f JOIN records r ON r.id=f.record_id"
           " WHERE fts MATCH ?")
    args = [match]
    if record_ids:
        sql += " AND f.record_id IN (%s)" % ",".join("?" * len(record_ids))
        args += list(record_ids)
    sql += " ORDER BY rank LIMIT ?"
    args.append(limit)
    return conn().execute(sql, args).fetchall()


def _tokenize(q: str):
    """中文没有空格分词：把查询切成 2-gram 片段做 FTS 召回，够用且无额外依赖。"""
    out, i = [], 0
    while i < len(q):
        if q[i].isascii() and (q[i].isalnum() or q[i] == "_"):
            j = i
            while j < len(q) and (q[j].isascii() and (q[j].isalnum() or q[j] == "_")):
                j += 1
            out.append(q[i:j].lower())
            i = j
        else:
            if i + 2 <= len(q):
                out.append(q[i:i + 2])
            i += 1
    return out or [q[:2]]


# ------------------------------------------------------------ 导出镜像（可再生）

def export_mirror(record_id: str):
    """把生效内容写成 transcripts/<id>/{segments.jsonl, transcript.md}；失败不影响主流程。"""
    import datetime
    try:
        row = record_row(record_id)
        if row is None:
            return
        segs = effective_segments(record_id)
        d = config.transcript_dir(record_id)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "segments.jsonl"), "w", encoding="utf-8") as f:
            for s in segs:
                f.write(json.dumps({
                    "id": s["id"], "t": s["t_ms"], "e": s["e_ms"],
                    "spk": s["spk"], "text": s["text"], "edited": bool(s["edited"]),
                }, ensure_ascii=False) + "\n")
        with open(os.path.join(d, "transcript.md"), "w", encoding="utf-8") as f:
            t = datetime.datetime.fromtimestamp(row["created_at"] / 1000)
            f.write("# %s ｜ %s\n\n" % (row["title"] or "未命名", t.strftime("%m-%d %H:%M")))
            cur = None
            for s in segs:
                if s["spk"] != cur:
                    cur = s["spk"]
                    f.write("\n**%s**\n\n" % _spk_name(cur))
                f.write("`[%s → %s]` %s\n\n" % (
                    _fmt_ms(s["t_ms"]), _fmt_ms(s["e_ms"]), s["text"]))
    except Exception:
        pass


def _spk_name(spk):
    return "发言人 %d" % (spk + 1) if spk is not None and spk >= 0 else "说话人未知"


def _fmt_ms(ms):
    s = int(ms / 1000)
    return "%02d:%02d.%02d" % (s // 60, s % 60, (ms % 1000) // 10)
