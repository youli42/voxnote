"""v2 维护：保留策略、备份、清理、存储统计、诊断。

原则（见 docs/设计/架构设计.md §3.6）：
  - 删除只做标记；真正的清理是显式动作，默认 dry-run；
  - 备份用 SQLite 在线 backup，保留 N 代（默认 7）；
  - 旧尝试超过保留代数/天数可清，生效尝试永远保留；修订历史不自动删。
"""
import shutil
import sqlite3
import time
from pathlib import Path

from . import config, db, jobs, store

DEFAULTS = {
    "tombstone_retention_days": "90",
    "attempt_keep": "3",
    "attempt_retention_days": "30",
    "backup_keep": "7",
}
SETTABLE = set(DEFAULTS)


def get_setting(conn, key: str) -> str:
    with db.lock:
        row = conn.execute("SELECT v FROM settings WHERE k=?", (key,)).fetchone()
    return row["v"] if row else DEFAULTS.get(key, "")


def set_setting(conn, key: str, value) -> None:
    if key not in SETTABLE:
        raise ValueError("不允许修改设置 %s" % key)
    if key.endswith("_days") or key.endswith("_keep"):
        try:
            if int(value) < 0:
                raise ValueError
        except (TypeError, ValueError):
            raise ValueError("%s 必须是非负整数" % key)
    with db.lock, conn:
        conn.execute("INSERT OR REPLACE INTO settings(k, v) VALUES(?,?)", (key, str(value)))


def settings(conn) -> dict:
    return {k: get_setting(conn, k) for k in DEFAULTS}


def _db_path(conn) -> Path:
    return Path(config.db_path())


def _data_dir(conn) -> Path:
    return _db_path(conn).parent


def _backup_dir(conn) -> Path:
    d = _data_dir(conn) / "backups"
    d.mkdir(parents=True, exist_ok=True)
    return d


def backups(conn) -> list:
    d = _backup_dir(conn)
    out = []
    for p in sorted(d.glob("voxnote-*.db"), reverse=True):
        out.append({"name": p.name, "bytes": p.stat().st_size,
                    "created_at": int(p.stat().st_mtime * 1000)})
    return out


def _prune_backups(conn):
    keep = max(1, int(get_setting(conn, "backup_keep")))
    files = sorted(_backup_dir(conn).glob("voxnote-*.db"), reverse=True)
    removed = []
    for p in files[keep:]:
        try:
            p.unlink()
            removed.append(p.name)
        except OSError:
            pass
    return removed


def backup(conn) -> dict:
    """在线备份一份 SQLite，并按 backup_keep 清理旧代。"""
    d = _backup_dir(conn)
    name = "voxnote-%s.db" % time.strftime("%Y%m%d-%H%M%S")
    dest = d / name
    n = 1
    while dest.exists():
        dest = d / ("voxnote-%s-%d.db" % (time.strftime("%Y%m%d-%H%M%S"), n))
        n += 1
    with db.lock:
        target = sqlite3.connect(str(dest))
        try:
            conn.backup(target)
        finally:
            target.close()
    removed = _prune_backups(conn)
    return {"name": dest.name, "path": str(dest),
            "bytes": dest.stat().st_size, "pruned": removed}


def _audio_bytes(path):
    try:
        return Path(path).stat().st_size if path else 0
    except OSError:
        return 0


def storage(conn) -> dict:
    with db.lock:
        rec = conn.execute(
            "SELECT COUNT(*) n, SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END) d"
            " FROM records").fetchone()
        audio = conn.execute("SELECT SUM(bytes) s FROM records").fetchone()["s"] or 0
        deleted_audio = conn.execute(
            "SELECT SUM(bytes) s FROM records WHERE deleted_at IS NOT NULL").fetchone()["s"] or 0
        attempts = conn.execute("SELECT COUNT(*) n FROM transcript_attempts").fetchone()["n"]
        edits = conn.execute("SELECT COUNT(*) n FROM edits").fetchone()["n"]
    db_path = _db_path(conn)
    bdir = _backup_dir(conn)
    return {
        "records": rec["n"] or 0,
        "deleted_records": rec["d"] or 0,
        "audio_bytes": audio,
        "deleted_audio_bytes": deleted_audio,
        "attempts": attempts,
        "edits": edits,
        "db_bytes": db_path.stat().st_size if db_path.exists() else 0,
        "backups_bytes": sum(p.stat().st_size for p in bdir.glob("voxnote-*.db")),
    }


def cleanup(conn, dry_run: bool = True) -> dict:
    """清理旧墓碑与超期旧尝试；dry_run=True 只统计不删。"""
    tomb_days = max(0, int(get_setting(conn, "tombstone_retention_days")))
    att_days = max(0, int(get_setting(conn, "attempt_retention_days")))
    att_keep = max(1, int(get_setting(conn, "attempt_keep")))
    now = int(time.time() * 1000)
    cut = now - tomb_days * 86400000
    att_cut = now - att_days * 86400000

    with db.lock:
        tombstones = [dict(r) for r in conn.execute(
            "SELECT id, audio_path, bytes FROM records"
            " WHERE deleted_at IS NOT NULL AND deleted_at < ?", (cut,)).fetchall()]

        attempts = []
        records = [r["id"] for r in conn.execute("SELECT id FROM records").fetchall()]
        for rid in records:
            active = conn.execute(
                "SELECT active_attempt_id FROM records WHERE id=?", (rid,)).fetchone()
            active_id = active["active_attempt_id"] if active else None
            rows = [dict(r) for r in conn.execute(
                "SELECT id, created_at FROM transcript_attempts WHERE record_id=?"
                " ORDER BY created_at DESC, id DESC", (rid,)).fetchall()]
            keep = set()
            if active_id:
                keep.add(active_id)
            keep |= {r["id"] for r in rows[:att_keep]}
            attempts += [r for r in rows
                         if r["id"] not in keep and r["created_at"] < att_cut]

    bytes_freed = sum(int(t.get("bytes") or _audio_bytes(t.get("audio_path")))
                      for t in tombstones)
    if not dry_run:
        with db.lock:
            for t in tombstones:
                path = t.get("audio_path")
                if path:
                    try:
                        Path(path).unlink()
                    except OSError:
                        pass
            for a in attempts:
                conn.execute("DELETE FROM transcript_attempts WHERE id=?", (a["id"],))
            for t in tombstones:
                conn.execute("DELETE FROM records WHERE id=?", (t["id"],))
            conn.commit()

    return {
        "dry_run": bool(dry_run),
        "tombstones": len(tombstones),
        "attempts": len(attempts),
        "bytes": bytes_freed,
    }


def stats(conn) -> dict:
    """控制台工作台统计（见控制台补充接口 §1）。

    口径（契约未细化的部分在此说明）：
      - records / records_today / audio_bytes / audio_hours 只算未删除记录；
      - records_today 按 recorded_at（录制时间）落在本地当天；
      - last_sync_at 优先取设备最近上报时间，其次取最近一条 change 的时间。
    """
    lt = time.localtime()
    today0 = int(time.mktime(
        (lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1)) * 1000)
    with db.lock:
        rec = conn.execute(
            "SELECT COUNT(*) n,"
            " COALESCE(SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),0) d"
            " FROM records").fetchone()
        today = conn.execute(
            "SELECT COUNT(*) n FROM records"
            " WHERE deleted_at IS NULL AND recorded_at>=?", (today0,)).fetchone()["n"]
        visible = conn.execute(
            "SELECT COALESCE(SUM(bytes),0) b, COALESCE(SUM(dur_ms),0) ms"
            " FROM records WHERE deleted_at IS NULL").fetchone()
        edited = conn.execute(
            "SELECT COUNT(DISTINCT record_id) n FROM edits").fetchone()["n"]
        attempts = conn.execute(
            "SELECT COUNT(*) n FROM transcript_attempts").fetchone()["n"]
        devices = conn.execute("SELECT COUNT(*) n FROM devices").fetchone()["n"]
        last_sync = conn.execute(
            "SELECT MAX(last_seen_at) m FROM devices").fetchone()["m"]
        if last_sync is None:
            last_sync = conn.execute(
                "SELECT MAX(created_at) m FROM changes").fetchone()["m"]
    return {
        "records": int(rec["n"]) - int(rec["d"]),
        "records_today": int(today),
        "deleted": int(rec["d"]),
        "audio_bytes": int(visible["b"]),
        "audio_hours": round(int(visible["ms"]) / 3600000.0, 1),
        "jobs": jobs.counts(conn),
        "edited_records": int(edited),
        "attempts": int(attempts),
        "devices": int(devices),
        "last_sync_at": int(last_sync or 0),
    }


def diagnostics(conn) -> dict:
    data = _data_dir(conn)
    try:
        usage = shutil.disk_usage(str(data))
        disk = {"free": usage.free, "total": usage.total}
    except OSError:
        disk = {"free": 0, "total": 0}
    st = storage(conn)
    with db.lock:
        job_rows = {r["state"]: r["n"] for r in conn.execute(
            "SELECT state, COUNT(*) n FROM jobs GROUP BY state").fetchall()}
        latest = conn.execute("SELECT COALESCE(MAX(rev),0) r FROM changes").fetchone()["r"]
        counts = conn.execute("SELECT COUNT(*) n FROM changes").fetchone()["n"]
        engines = conn.execute("SELECT COUNT(*) n FROM engines").fetchone()["n"]
    return {
        "schema_version": store.schema_version(conn),
        "db_path": str(_db_path(conn)),
        "storage": st,
        "jobs": job_rows,
        "engines": engines,
        "changes": {"latest_rev": latest, "count": counts},
        "backups": len(backups(conn)),
        "disk": disk,
    }
