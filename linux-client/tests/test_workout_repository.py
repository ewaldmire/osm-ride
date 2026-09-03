from pathlib import Path

import pytest

from osm_ride_linux.ride.models import WorkoutSegment
from osm_ride_linux.ride.workout_repository import WorkoutRepository, WorkoutRepositoryError

_ERG_TEXT = "[COURSE DATA]\n0.00 100\n5.00 100\n5.00 200\n10.00 200\n[END COURSE DATA]\n"


@pytest.fixture
def repo_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


@pytest.fixture
def erg_file(tmp_path: Path) -> Path:
    path = tmp_path / "my_workout.erg"
    path.write_text(_ERG_TEXT)
    return path


def test_import_erg_workout(repo_dir: Path, erg_file: Path):
    repo = WorkoutRepository(data_dir=repo_dir)
    workout = repo.import_workout(erg_file, "my_workout.erg", ftp_watts=None)
    assert workout.name == "my_workout"
    # 4 data points -> 3 pairwise segments (see test_erg_parser.py for why).
    assert len(workout.segments) == 3
    assert len(repo.workouts) == 1


def test_save_created_workout_and_reload(repo_dir: Path):
    repo = WorkoutRepository(data_dir=repo_dir)
    segments = [WorkoutSegment(0, 300, 150, 150), WorkoutSegment(300, 600, 150, 250)]
    workout = repo.save_created_workout(None, "Built Workout", segments)
    assert workout.total_duration_seconds == 600

    fresh = WorkoutRepository(data_dir=repo_dir)
    reloaded = fresh.get_workout(workout.id)
    assert reloaded is not None
    assert reloaded.segments == segments


def test_rename_preserves_segments_as_real_objects_not_dicts(repo_dir: Path):
    # Regression test: an earlier version of rename_workout used
    # Workout(**{**asdict(w), "name": ...}) which silently corrupts nested dataclasses
    # (segments) into plain dicts. dataclasses.replace() is the fix - this locks that in.
    repo = WorkoutRepository(data_dir=repo_dir)
    segments = [WorkoutSegment(0, 300, 150, 150)]
    workout = repo.save_created_workout(None, "Original Name", segments)

    repo.rename_workout(workout.id, "Renamed")
    renamed = repo.get_workout(workout.id)
    assert renamed.name == "Renamed"
    assert isinstance(renamed.segments[0], WorkoutSegment)
    assert renamed.segments[0].start_watts == 150  # attribute access, not dict-style


def test_save_created_workout_updates_in_place_with_existing_id(repo_dir: Path):
    repo = WorkoutRepository(data_dir=repo_dir)
    first = repo.save_created_workout(None, "V1", [WorkoutSegment(0, 100, 100, 100)])
    repo.save_created_workout(first.id, "V2", [WorkoutSegment(0, 200, 150, 150)])
    assert len(repo.workouts) == 1
    assert repo.get_workout(first.id).name == "V2"


def test_save_created_workout_rejects_empty_segments(repo_dir: Path):
    repo = WorkoutRepository(data_dir=repo_dir)
    with pytest.raises(WorkoutRepositoryError):
        repo.save_created_workout(None, "Empty", [])


def test_delete_workout(repo_dir: Path):
    repo = WorkoutRepository(data_dir=repo_dir)
    workout = repo.save_created_workout(None, "To Delete", [WorkoutSegment(0, 100, 100, 100)])
    repo.delete_workout(workout.id)
    assert len(repo.workouts) == 0
