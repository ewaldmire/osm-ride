from pathlib import Path

import pytest

from osm_ride_linux.weight.repository import WeightRepository


@pytest.fixture
def repo_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


def test_add_entry_persists_and_returns_it(repo_dir: Path):
    repo = WeightRepository(data_dir=repo_dir)
    entry = repo.add_entry(75.5, recorded_at_epoch_millis=1000)
    assert len(repo.entries) == 1
    assert entry.weight_kg == 75.5
    assert entry.recorded_at_epoch_millis == 1000


def test_new_entries_are_sorted_newest_first(repo_dir: Path):
    repo = WeightRepository(data_dir=repo_dir)
    older = repo.add_entry(76.0, recorded_at_epoch_millis=1000)
    newer = repo.add_entry(75.0, recorded_at_epoch_millis=2000)
    assert [e.id for e in repo.entries] == [newer.id, older.id]


def test_index_persists_across_fresh_instance(repo_dir: Path):
    repo = WeightRepository(data_dir=repo_dir)
    repo.add_entry(74.2, recorded_at_epoch_millis=1000)

    fresh = WeightRepository(data_dir=repo_dir)
    assert len(fresh.entries) == 1
    assert fresh.entries[0].weight_kg == 74.2


def test_delete_entry_removes_it(repo_dir: Path):
    repo = WeightRepository(data_dir=repo_dir)
    entry = repo.add_entry(73.0, recorded_at_epoch_millis=1000)
    repo.delete_entry(entry.id)
    assert repo.entries == []


def test_on_entries_changed_fires_on_add_and_delete(repo_dir: Path):
    repo = WeightRepository(data_dir=repo_dir)
    calls = []
    repo.on_entries_changed = lambda entries: calls.append(len(entries))

    entry = repo.add_entry(70.0, recorded_at_epoch_millis=1000)
    repo.delete_entry(entry.id)

    assert calls == [1, 0]
