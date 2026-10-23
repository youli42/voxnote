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

    def transcribe(self, audio_path: str, record, on_progress=None):
        """返回 [{'t_ms','e_ms','text','spk'}]；失败抛异常。

        on_progress(fraction 0..1) 尽力而为，不要求精确。
        """
        raise NotImplementedError


ENGINES = {}


def register(e: Engine):
    ENGINES[e.id] = e


def get(engine_id: str) -> Engine:
    return ENGINES.get(engine_id)


def all_infos() -> list:
    return [e.info() for e in ENGINES.values()]


def _load_builtin():
    """import 各引擎模块以触发注册；单个引擎缺依赖不影响其它。"""
    from . import local_whisper, cloud_openai  # noqa: F401


_load_builtin()
