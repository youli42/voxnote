"""v2 导出：md / srt（文字导出物，不是事实来源）。"""
from fastapi import HTTPException

from . import edits


def _filename(record: dict, ext: str) -> str:
    stamp = (record.get("legacy_id") or record["id"])[:32]
    return "%s.%s" % (stamp, ext)


def _fmt_srt(ms: int) -> str:
    ms = max(0, int(ms))
    h, rem = divmod(ms, 3600000)
    m, rem = divmod(rem, 60000)
    s, ms = divmod(rem, 1000)
    return "%02d:%02d:%02d,%03d" % (h, m, s, ms)


def build(conn, record_id: str, fmt: str) -> tuple:
    tr = edits.transcript(conn, record_id)
    if tr is None:
        raise HTTPException(404, "记录不存在")
    rec = tr["record"]
    segs = tr["segments"]
    if fmt == "md":
        lines = ["# %s" % (rec.get("title") or rec["id"]), ""]
        lines.append("- 时间：%s" % rec.get("recorded_at"))
        lines.append("- 状态：%s" % rec.get("status"))
        lines.append("")
        for seg in segs:
            if seg.get("deleted"):
                continue
            lines.append(seg.get("text", ""))
        body = "\n".join(lines).rstrip() + "\n"
        return body, "markdown", _filename(rec, "md")
    if fmt == "srt":
        blocks = []
        for i, seg in enumerate(segs, 1):
            if seg.get("deleted"):
                continue
            blocks.append("%d\n%s --> %s\n%s\n" % (
                i, _fmt_srt(seg["t_ms"]), _fmt_srt(seg["e_ms"]), seg.get("text", "")))
        return "\n".join(blocks), "application/x-subrip", _filename(rec, "srt")
    raise HTTPException(400, "format 只能是 md 或 srt")
