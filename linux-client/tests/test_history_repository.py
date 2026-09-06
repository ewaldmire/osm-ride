from pathlib import Path

import pytest

from osm_ride_linux.ride.history_repository import RideHistoryRepository
from osm_ride_linux.ride.models import RideState, RideStats


@pytest.fixture
def repo_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


def _finished_stats() -> RideStats:
    return RideStats(
        state=RideState.FINISHED,
        distance_meters=5000.0,
        total_distance_meters=5000.0,
        progress_fraction=1.0,
        elapsed_seconds=1200.0,
        avg_speed_mps=4.17,
        avg_power_watts=180.0,
        avg_cadence_rpm=85.0,
        avg_heart_rate_bpm=145.0,
    )


def test_save_ride_writes_gpx_and_index_entry(repo_dir: Path):
    repo = RideHistoryRepository(data_dir=repo_dir)
    record = repo.save_ride("Test Route", "route-1", _finished_stats(), "<gpx>fake content</gpx>")
    assert len(repo.rides) == 1
    assert record.title == "Test Route"
    assert record.estimated_kilocalories == pytest.approx(180.0 * 1200.0 / 1000.0)
    assert repo.gpx_file(record).read_text() == "<gpx>fake content</gpx>"


def test_new_rides_are_inserted_newest_first(repo_dir: Path):
    repo = RideHistoryRepository(data_dir=repo_dir)
    first = repo.save_ride("Route A", "route-a", _finished_stats(), "<gpx/>")
    second = repo.save_ride("Route B", "route-b", _finished_stats(), "<gpx/>")
    assert [r.id for r in repo.rides] == [second.id, first.id]


def test_update_ride_sets_title_and_notes(repo_dir: Path):
    repo = RideHistoryRepository(data_dir=repo_dir)
    record = repo.save_ride("Test Route", "route-1", _finished_stats(), "<gpx/>")
    repo.update_ride(record.id, "Custom Title", "Felt strong today")
    updated = next(r for r in repo.rides if r.id == record.id)
    assert updated.title == "Custom Title"
    assert updated.notes == "Felt strong today"


def test_index_persists_across_fresh_instance(repo_dir: Path):
    repo = RideHistoryRepository(data_dir=repo_dir)
    record = repo.save_ride("Test Route", "route-1", _finished_stats(), "<gpx/>")
    repo.update_ride(record.id, "Custom Title", "notes here")

    fresh = RideHistoryRepository(data_dir=repo_dir)
    assert len(fresh.rides) == 1
    assert fresh.rides[0].title == "Custom Title"
    assert fresh.rides[0].notes == "notes here"


def test_delete_ride_removes_record_and_gpx_file(repo_dir: Path):
    repo = RideHistoryRepository(data_dir=repo_dir)
    record = repo.save_ride("Test Route", "route-1", _finished_stats(), "<gpx/>")
    gpx_path = repo.gpx_file(record)
    assert gpx_path.exists()

    repo.delete_ride(record.id)
    assert len(repo.rides) == 0
    assert not gpx_path.exists()
