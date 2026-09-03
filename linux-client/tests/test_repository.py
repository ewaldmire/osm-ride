from pathlib import Path

import pytest

from osm_ride_linux.route.models import RouteWaypoint
from osm_ride_linux.route.repository import RouteRepository

_GPX = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>Repo Test Route</name><trkseg>
    <trkpt lat="40.11" lon="-88.20"><ele>220</ele></trkpt>
    <trkpt lat="40.12" lon="-88.20"><ele>225</ele></trkpt>
  </trkseg></trk>
</gpx>"""


@pytest.fixture
def repo_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


@pytest.fixture
def source_gpx(tmp_path: Path) -> Path:
    path = tmp_path / "source.gpx"
    path.write_text(_GPX)
    return path


def test_import_and_load_round_trip(repo_dir: Path, source_gpx: Path):
    repo = RouteRepository(data_dir=repo_dir)
    summary = repo.import_gpx(source_gpx, "source")
    assert len(repo.routes) == 1

    loaded = repo.load_route(summary.id)
    assert loaded is not None
    assert loaded.name == "Repo Test Route"
    assert len(loaded.points) == 2


def test_rename_updates_in_place(repo_dir: Path, source_gpx: Path):
    repo = RouteRepository(data_dir=repo_dir)
    summary = repo.import_gpx(source_gpx, "source")
    repo.rename_route(summary.id, "Renamed Route")
    assert repo.get_route_summary(summary.id).name == "Renamed Route"


def test_index_persists_across_fresh_instance(repo_dir: Path, source_gpx: Path):
    repo = RouteRepository(data_dir=repo_dir)
    summary = repo.import_gpx(source_gpx, "source")
    repo.rename_route(summary.id, "Renamed Route")

    fresh = RouteRepository(data_dir=repo_dir)
    assert len(fresh.routes) == 1
    assert fresh.routes[0].name == "Renamed Route"


def test_save_created_route_persists_waypoints(repo_dir: Path):
    repo = RouteRepository(data_dir=repo_dir)
    waypoints = [RouteWaypoint(40.11, -88.20), RouteWaypoint(40.12, -88.20)]
    summary = repo.save_created_route(None, "Created Route", _GPX, waypoints)
    assert summary.waypoints == waypoints

    fresh = RouteRepository(data_dir=repo_dir)
    reloaded = fresh.get_route_summary(summary.id)
    assert reloaded.waypoints == waypoints


def test_save_created_route_updates_in_place_when_given_existing_id(repo_dir: Path):
    repo = RouteRepository(data_dir=repo_dir)
    first = repo.save_created_route(None, "Created Route", _GPX, [RouteWaypoint(1, 1), RouteWaypoint(2, 2)])
    repo.save_created_route(first.id, "Created Route", _GPX, [RouteWaypoint(1, 1), RouteWaypoint(3, 3)])
    assert len(repo.routes) == 1  # updated in place, not duplicated
    assert repo.get_route_summary(first.id).waypoints == [RouteWaypoint(1, 1), RouteWaypoint(3, 3)]


def test_delete_removes_route_and_file(repo_dir: Path, source_gpx: Path):
    repo = RouteRepository(data_dir=repo_dir)
    summary = repo.import_gpx(source_gpx, "source")
    gpx_file = repo_dir / "routes" / summary.file_name
    assert gpx_file.exists()

    repo.delete_route(summary.id)
    assert len(repo.routes) == 0
    assert not gpx_file.exists()
