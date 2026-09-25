from macdeck_agent import store


def test_write_is_atomic_and_leaves_no_temp_file(tmp_path):
    path = tmp_path / "pins.json"
    store.write_json_atomic(path, ["a", "b"])
    assert store.read_json_list(path) == ["a", "b"]
    assert [p.name for p in tmp_path.iterdir()] == ["pins.json"]


def test_unreadable_or_odd_files_read_as_empty(tmp_path):
    path = tmp_path / "pins.json"
    assert store.read_json_list(path) == []            # missing
    path.write_text('["a", 3, null, "b"')              # truncated (a half-written file)
    assert store.read_json_list(path) == []
    path.write_text('{"not": "a list"}')
    assert store.read_json_list(path) == []
    path.write_text('["a", 3, null, "b"]')             # non-strings dropped
    assert store.read_json_list(path) == ["a", "b"]
