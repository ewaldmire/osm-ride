from osm_ride_linux.ble.models import TrainerSample
from osm_ride_linux.ride.engine import RideEngine
from osm_ride_linux.ride.models import RideState, Workout, WorkoutSegment
from osm_ride_linux.route.models import Route, RoutePoint


def _straight_route(length_meters: float = 1000.0) -> Route:
    # Two points ~length_meters apart along a meridian (longitude fixed), with elevation so
    # grade calculations have something to work with.
    lat_span = length_meters / 111_195.0  # meters per degree latitude, roughly
    return Route(
        id="r1",
        name="Straight Route",
        points=[
            RoutePoint(lat=40.0, lon=-88.0, elevation_meters=200.0, cumulative_distance_meters=0.0),
            RoutePoint(
                lat=40.0 + lat_span,
                lon=-88.0,
                elevation_meters=210.0,
                cumulative_distance_meters=length_meters,
            ),
        ],
        total_distance_meters=length_meters,
        elevation_gain_meters=10.0,
    )


def test_ftms_distance_drives_progress_and_position():
    # The trainer's FTMS "total distance" is often a lifetime/session odometer rather than zero
    # at ride start, so the engine baselines against the FIRST sample it sees and reports delta
    # from there - the first sample always reads back as distance 0.
    engine = RideEngine(_straight_route(1000.0))
    engine.start()
    assert engine.stats.state == RideState.RIDING

    engine.on_trainer_sample(TrainerSample(speed_mps=5.0, power_watts=150, total_distance_meters=5000.0))
    assert engine.stats.distance_meters == 0.0

    engine.on_trainer_sample(TrainerSample(speed_mps=5.0, power_watts=150, total_distance_meters=5100.0))
    assert abs(engine.stats.distance_meters - 100.0) < 0.01
    assert abs(engine.stats.progress_fraction - 0.1) < 0.001
    assert engine.stats.position is not None
    # 10% of the way along a route climbing 200 -> 210m -> exactly 201m.
    assert abs(engine.stats.position.elevation_meters - 201.0) < 0.01

    engine.on_trainer_sample(TrainerSample(speed_mps=5.0, power_watts=160, total_distance_meters=6000.0))
    assert engine.stats.state == RideState.FINISHED
    assert abs(engine.stats.distance_meters - 1000.0) < 0.01


def test_ftms_distance_never_goes_backward():
    engine = RideEngine(_straight_route(1000.0))
    engine.start()
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1000.0))  # baseline
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1500.0))  # +500
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1490.0))  # a noisy/glitchy dip
    assert engine.stats.distance_meters == 500.0  # distance is monotonic, ignores the dip


def test_speed_integration_fallback_when_no_total_distance():
    # Explicit timestamps (rather than TrainerSample's default_factory=time.time) so the elapsed
    # gap between samples is exact and deterministic, not dependent on real wall-clock timing.
    engine = RideEngine(_straight_route(1000.0))
    engine.start()

    engine.on_trainer_sample(TrainerSample(speed_mps=10.0, total_distance_meters=None, timestamp=1000.0))
    assert engine.stats.distance_meters == 0.0  # first sample only establishes a baseline

    engine.on_trainer_sample(TrainerSample(speed_mps=10.0, total_distance_meters=None, timestamp=1002.0))
    assert abs(engine.stats.distance_meters - 20.0) < 0.01  # 10 m/s * 2s


def test_pause_resume_and_finish_manually():
    engine = RideEngine(_straight_route(1000.0))
    engine.start()
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1000.0))  # baseline
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1200.0))  # +200
    engine.pause()
    assert engine.stats.state == RideState.PAUSED

    # Samples are ignored while paused.
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1900.0))
    assert engine.stats.distance_meters == 200.0

    engine.start()  # resume
    assert engine.stats.state == RideState.RIDING

    engine.finish_manually()
    assert engine.stats.state == RideState.FINISHED


def test_workout_target_watts_interpolates_ramp():
    engine = RideEngine(_straight_route(1000.0))
    engine.workout = Workout(
        id="w1",
        name="Ramp Test",
        segments=[WorkoutSegment(start_seconds=0, end_seconds=100, start_watts=100, end_watts=200)],
        total_duration_seconds=100,
    )
    assert engine._target_watts_at(0) == 100
    assert engine._target_watts_at(50) == 150
    assert engine._target_watts_at(100) == 200  # holds the last segment's value past its end


def test_workout_free_ride_segment_has_no_target():
    engine = RideEngine(_straight_route(1000.0))
    engine.workout = Workout(
        id="w1",
        name="Free Ride",
        segments=[WorkoutSegment(start_seconds=0, end_seconds=100, start_watts=None, end_watts=None)],
        total_duration_seconds=100,
    )
    assert engine._target_watts_at(50) is None


def test_grade_reflects_route_elevation_change():
    # 1000m route climbing 10m -> 1% average grade.
    engine = RideEngine(_straight_route(1000.0))
    grade = engine._grade_at(500.0)  # centered in the route, window comfortably inside both ends
    assert grade is not None
    assert abs(grade - 1.0) < 0.1


def test_stats_change_callback_fires():
    engine = RideEngine(_straight_route(1000.0))
    seen = []
    engine.on_stats_changed = lambda s: seen.append(s)
    engine.start()
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1000.0))  # baseline
    engine.on_trainer_sample(TrainerSample(total_distance_meters=1100.0))  # +100
    assert len(seen) >= 1
    assert seen[-1].distance_meters == 100.0
