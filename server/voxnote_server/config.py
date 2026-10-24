"""路径与运行配置。

数据目录（默认 ./data，环境变量 VOXNOTE_DATA 或 --data 覆盖）：
    data/voxnote.db                 SQLite：索引、队列、设置、修订层
    data/audio/<yyyy.MM>/<原名>      音频原件，按月分目录（与 App 端习惯一致）
    data/transcripts/<id>/           可再生的导出镜像：segments.jsonl / transcript.md
    data/config.json                 位置覆盖（音频目录 / 数据库文件），控制台可改

文件是音频的真源，SQLite 是运行态索引；删库可从文件+重新转写重建（人工修订除外，
修订只在 SQLite 里，后续提供导出）。
"""
import json
import os


def data_dir() -> str:
    d = os.environ.get("VOXNOTE_DATA") or os.path.join(os.getcwd(), "data")
    return d


def config_file() -> str:
    """位置覆盖配置。固定放默认数据目录下：audio/db 位置可以被它改写，
    它自己的锚点不能跟着搬，否则改完就找不到自己了。"""
    return os.path.join(data_dir(), "config.json")


def _overrides() -> dict:
    try:
        with open(config_file(), "r", encoding="utf-8") as f:
            o = json.load(f)
        return o if isinstance(o, dict) else {}
    except Exception:
        return {}


def _resolve(p: str) -> str:
    """覆盖路径支持相对写法，基于默认数据目录解析。"""
    return p if os.path.isabs(p) else os.path.join(data_dir(), p)


def audio_base() -> str:
    """音频根目录（覆盖值或默认 data/audio）。"""
    ov = _overrides().get("audio_dir")
    return _resolve(ov) if ov else os.path.join(data_dir(), "audio")


def audio_dir(ym: str = None) -> str:
    import datetime
    ym = ym or datetime.datetime.now().strftime("%Y.%m")
    return os.path.join(audio_base(), ym)


def transcript_dir(record_id: str) -> str:
    return os.path.join(data_dir(), "transcripts", record_id)


def db_path() -> str:
    ov = _overrides().get("db_path")
    return _resolve(ov) if ov else os.path.join(data_dir(), "voxnote.db")


def web_dir() -> str:
    """控制台静态文件目录。"""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")


def ensure_dirs():
    for d in (data_dir(), audio_base(), os.path.join(data_dir(), "transcripts")):
        os.makedirs(d, exist_ok=True)
