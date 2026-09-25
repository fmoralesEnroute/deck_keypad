"""Small JSON files the agent owns (pins, Home order)."""
import json
import os


def read_json_list(path):
    """A JSON list of strings, or [] if the file is missing or unreadable."""
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return []
    return [item for item in data if isinstance(item, str)] if isinstance(data, list) else []


def write_json_atomic(path, data):
    """Write via a temporary file and rename, so a crash mid-write never leaves a half file
    (which would read back as empty and silently lose pins or the Home order)."""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    os.replace(tmp, path)
