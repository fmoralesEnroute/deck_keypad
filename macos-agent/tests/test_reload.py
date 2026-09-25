import os
import threading
import time
import types

from macdeck_agent import config, reload


def test_restarts_on_any_module_change_but_never_into_broken_code(tmp_path, monkeypatch):
    (tmp_path / "macdeck_agent").mkdir()
    agent, module = tmp_path / "agent.py", tmp_path / "macdeck_agent" / "home.py"
    agent.write_text("x = 1\n")
    module.write_text("y = 1\n")
    monkeypatch.setattr(config, "HERE", tmp_path)
    execs = []
    real_sleep = time.sleep
    monkeypatch.setattr(reload, "time", types.SimpleNamespace(sleep=lambda s: real_sleep(0.05)))
    monkeypatch.setattr(reload, "os", types.SimpleNamespace(execv=lambda exe, args: execs.append(args)))
    stop = threading.Event()
    watcher = threading.Thread(target=reload.watch_for_code_changes, args=(stop,), daemon=True)
    watcher.start()
    real_sleep(0.2)

    def touch(path, text, offset):
        path.write_text(text)
        os.utime(path, (time.time() + offset,) * 2)
        real_sleep(0.3)

    touch(module, "y = (\n", 5)          # a module breaks: stay on the running version
    assert execs == []
    touch(module, "y = 2\n", 10)         # fixed: restart, via agent.py
    assert len(execs) == 1 and execs[0][1] == str(agent)

    stop.set()                           # must end before monkeypatch restores the real os/paths
    watcher.join(timeout=2)
    assert not watcher.is_alive()
