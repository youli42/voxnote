"""路径与运行配置。

数据目录（默认 ./data，环境变量 VOXNOTE_DATA 或 --data 覆盖）：
    data/voxnote.db                 SQLite：索引、队列、设置、修订层
    data/audio/<yyyy.MM>/<原名>      音频原件，按月分目录（与 App 端习惯一致）
    data/transcripts/<id>/           可再生的导出镜像：segments.jsonl / transcript.md

文件是音频的真源，SQLite 是运行态索引；删库可从文件+重新转写重建（人工修订除外，
修订只在 SQLite 里，后续提供导出）。
"""
import os


def data_dir() -> str:
    d = os.environ.get("VOXNOTE_DATA") or os.path.join(os.getcwd(), "data")
    return d


def audio_dir(ym: str = None) -> str:
    import datetime
    ym = ym or datetime.datetime.now().strftime("%Y.%m")
    return os.path.join(data_dir(), "audio", ym)


def transcript_dir(record_id: str) -> str:
    return os.path.join(data_dir(), "transcripts", record_id)


def db_path() -> str:
    return os.path.join(data_dir(), "voxnote.db")


def web_dir() -> str:
    """控制台静态文件目录。"""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")


def ensure_dirs():
    for d in (data_dir(), os.path.join(data_dir(), "audio"), os.path.join(data_dir(), "transcripts")):
        os.makedirs(d, exist_ok=True)
