from pathlib import Path

import pytest

from osm_ride_linux.weight.waist_repository import WaistRepository


@pytest.fixture
def repo_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


def test_add_entry_persists_and_returns_it(repo_dir: Path):
    repo = WaistRepository(data_dir=repo_dir)
    entry = repo.add_entry(85.5, recorded_at_epoch_millis=1000)
    assert len(repo.entries) == 1
    assert entry.waist_cm == 85.5
    assert entry.recorded_at_epoch_millis == 1000


def test_new_entries_are_sorted_newest_first(repo_dir: Path):
    repo = WaistRepository(data_dir=repo_dir)
    older = repo.add_entry(86.0, recorded_at_epoch_millis=1000)
    newer = repo.add_entry(85.0, recorded_at_epoch_millis=2000)
    assert [e.id for e in repo.entries] == [newer.id, older.id]


def test_index_persists_across_fresh_instance(repo_dir: Path):
    repo = WaistRepository(data_dir=repo_dir)
    repo.add_entry(84.2, recorded_at_epoch_millis=1000)

    fresh = WaistRepository(data_dir=repo_dir)
    assert len(fresh.entries) == 1
    assert fresh.entries[0].waist_cm == 84.2


def test_delete_entry_removes_it(repo_dir: Path):
    repo = WaistRepository(data_dir=repo_dir)
    entry = repo.add_entry(83.0, recorded_at_epoch_millis=1000)
    repo.delete_entry(entry.id)
    assert repo.entries == []


def test_on_entries_changed_fires_on_add_and_delete(repo_dir: Path):
    repo = WaistRepository(data_dir=repo_dir)
    calls = []
    repo.on_entries_changed = lambda entries: calls.append(len(entries))

    entry = repo.add_entry(80.0, recorded_at_epoch_millis=1000)
    repo.delete_entry(entry.id)

    assert calls == [1, 0]
