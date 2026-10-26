"""v2 连接管理：按数据库路径缓存，每线程独立连接（与 v1 同策略）。"""
import threading

from . import config, store

_local = threading.local()
_lock = threading.Lock()
_owned = {}
# 单用户服务器：所有读写串行化，避免共享连接上的事务/语句交错
lock = threading.RLock()


def conn():
    """当前线程的连接；路径变化（测试切换临时库）时重新初始化。"""
    path = config.db_path()
    current = getattr(_local, "conn", None)
    if current is not None and getattr(_local, "path", None) == path:
        return current
    with _lock:
        c = _owned.get(path)
        if c is None:
            c = store.init(path)
            _owned[path] = c
    _local.conn = c
    _local.path = path
    return c


def reset():
    """关闭所有缓存连接（测试/重启用）。"""
    with _lock:
        for c in _owned.values():
            try:
                c.close()
            except Exception:
                pass
        _owned.clear()
    _local.conn = None
    _local.path = None
