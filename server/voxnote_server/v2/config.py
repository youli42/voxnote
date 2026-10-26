"""v2 运行配置：数据库路径。

开发阶段 v2 使用独立库文件（默认 `data/voxnote-v2.db`），不碰 v1 的 `voxnote.db`；
可用环境变量 `VOXNOTE_V2_DB` 覆盖（测试/临时实例用）。
"""
import os
from pathlib import Path

from .. import config as base_config


def db_path() -> str:
    override = os.environ.get("VOXNOTE_V2_DB", "").strip()
    if override:
        return str(Path(override).expanduser().resolve())
    return str(Path(base_config.data_dir()) / "voxnote-v2.db")
