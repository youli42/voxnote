"""AI 对话：句级召回 + 原句引用。

流程：问题 → FTS5 召回相关句（可限定记录）→ 带编号片段拼进 prompt →
OpenAI 兼容 chat/completions（默认智谱 GLM）→ 回答里的 [S3] 引用映射回
{record_id, seg_id, t_ms, text}，控制台点击跳转记录库并定位播放。

未配置 Key 时返回 400 + 提示，控制台引导去设置页。
"""
import datetime

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional, List

from . import db

router = APIRouter()

SYSTEM_PROMPT = (
    "你是录音转写稿的助手。回答严格基于给出的编号片段；引用原句时使用 [S编号] 标记，"
    "例如 [S3]。片段里没有的信息要明说找不到，不要编造。用简体中文回答，条理清晰。"
)


class ChatReq(BaseModel):
    q: str
    record_ids: Optional[List[str]] = None   # 限定记录；空 = 全库召回


@router.post("/chat")
def chat(req: ChatReq):
    q = (req.q or "").strip()
    if not q:
        raise HTTPException(400, "问题为空")
    if not db.get("ai_key"):
        raise HTTPException(400, "AI 未配置：请在 设置 → AI 填入 API Key")

    hits = db.search_sentences(q, limit=14, record_ids=req.record_ids)
    if not hits:
        return {"answer": "转写稿里没有找到和问题相关的内容。", "citations": []}

    seen, cites, blocks = set(), [], []
    for h in hits:
        if h["seg_id"] in seen:
            continue
        seen.add(h["seg_id"])
        n = len(blocks) + 1
        try:
            t = datetime.datetime.fromtimestamp(h["created_at"] / 1000).strftime("%m-%d %H:%M")
        except (OSError, OverflowError, ValueError):
            t = ""  # 老库里可能有钳制前的极端时间戳，别让引用整段 500
        blocks.append("[S%d] %s《%s》%s" % (n, t, h["title"] or "未命名", h["text"]))
        cites.append({"ref": n, "record_id": h["record_id"], "seg_id": h["seg_id"],
                      "text": h["text"]})

    answer = _call_llm(req.q, "\n".join(blocks))
    return {"answer": answer, "citations": cites}


def _call_llm(q: str, context: str) -> str:
    base = (db.get("ai_base") or "").rstrip("/")
    key = db.get("ai_key") or ""
    model = db.get("ai_model") or "glm-4-flash"
    try:
        resp = httpx.post(
            base + "/chat/completions",
            headers={"Authorization": "Bearer " + key},
            json={
                "model": model,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": "问题：%s\n\n片段：\n%s" % (q, context)},
                ],
                "temperature": 0.3,
            },
            timeout=120)
    except httpx.HTTPError as t:
        raise HTTPException(502, "AI 服务连接失败：%s" % t)
    if resp.status_code != 200:
        raise HTTPException(502, "AI 服务返回 HTTP %d：%s"
                            % (resp.status_code, resp.text[:200]))
    try:
        return resp.json()["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, ValueError):
        raise HTTPException(502, "AI 响应格式异常")
