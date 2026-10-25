"""转写引擎插件接口。

约定（对应 App 端 ContentSource 的可拆卸设计）：
  - 每个引擎一个模块级单例，实现 available() / info() / transcribe()；
  - 核心只认这个接口，不 import 任何具体引擎；新引擎注册进 ENGINES 即可；
  - 可选依赖（faster-whisper 等）必须懒加载：没装 → available()=False，
    队列不会把任务分给它。
"""


class Engine:
    id = ""
    name = ""
    desc = ""

    def available(self) -> bool:
        return False

    def info(self) -> dict:
        return {"id": self.id, "name": self.name, "desc": self.desc,
                "available": self.available()}

    def transcribe(self, audio_path: str, record, on_progress=None, on_partial=None):
        """返回 [{'t_ms','e_ms','text','spk'}]；失败抛异常。

        on_progress(fraction 0..1) 尽力而为，不要求精确。
        on_partial(部分结果列表) 边转写边回调（实现方可忽略）；队列侧
        负责节流落库，手机端轮询即可实时看到内容增长。
        """
        raise NotImplementedError


ENGINES = {}


def register(e: Engine):
    ENGINES[e.id] = e


def get(engine_id: str) -> Engine:
    return ENGINES.get(engine_id)


def disabled_ids() -> list:
    """控制台里被停用的引擎（disabled_engines 设置，逗号分隔的引擎 id）。"""
    from .. import db
    raw = db.get("disabled_engines") or ""
    return [x.strip() for x in raw.split(",") if x.strip()]


def enabled(engine_id: str) -> bool:
    """控制台停用的引擎不参与自动入队与手动转写；文件可用性另由 available() 管。"""
    return engine_id not in disabled_ids()


def all_infos() -> list:
    return [e.info() for e in ENGINES.values()]


def _load_builtin():
    """import 各引擎模块以触发注册；单个引擎缺依赖不影响其它。"""
    from . import local_whisper, cloud_openai, local_llama, sherpa_asr  # noqa: F401


_load_builtin()
