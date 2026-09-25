"""Restart the agent when its code changes, so an update never runs half-applied."""
import os
import py_compile
import sys
import time

from . import config


def _sources():
    return [config.HERE / "agent.py", *sorted((config.HERE / "macdeck_agent").glob("*.py"))]


def _mtimes():
    stamps = {}
    for path in _sources():
        try:
            stamps[path] = path.stat().st_mtime
        except OSError:
            pass
    return stamps


def watch_for_code_changes(stop=None):
    """Poll the agent's .py files; when one changes and all of them compile, re-exec the agent.
    If something doesn't compile, keep running the current version and say why.
    Phones reconnect by themselves within a second or two. `stop` (a threading.Event) ends it."""
    seen = _mtimes()
    while not (stop and stop.is_set()):
        time.sleep(2)
        current = _mtimes()
        if current == seen:
            continue
        seen = current
        try:
            for path in current:
                py_compile.compile(str(path), doraise=True)
        except py_compile.PyCompileError as e:
            print(f"The agent's code changed but doesn't compile; keeping the running version.\n{e.msg}")
            continue
        print("The agent's code changed; restarting to apply it…", flush=True)
        agent = str(config.HERE / "agent.py")
        os.execv(sys.executable, [sys.executable, agent, *sys.argv[1:]])
