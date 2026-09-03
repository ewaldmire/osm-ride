from osm_ride_linux.ride.gpx_writer import write
from osm_ride_linux.ride.models import RecordedTrackPoint
from osm_ride_linux.route.gpx import parse as parse_gpx


def test_written_gpx_round_trips_through_the_gpx_parser():
    points = [
        RecordedTrackPoint(
            timestamp=1_700_000_000.0, lat=40.11, lon=-88.20, elevation_meters=220.0, heart_rate_bpm=140, cadence_rpm=88.0
        ),
        RecordedTrackPoint(
            timestamp=1_700_000_010.0, lat=40.12, lon=-88.20, elevation_meters=225.0, heart_rate_bpm=142, cadence_rpm=90.0
        ),
    ]
    gpx_text = write("Test Ride", points)

    parsed = parse_gpx(gpx_text)
    assert parsed.name == "Test Ride"
    assert len(parsed.points) == 2
    assert parsed.points[0].lat == 40.11
    assert parsed.points[1].elevation_meters == 225.0


def test_escapes_xml_special_characters_in_name():
    gpx_text = write("Ride & <Test> \"quoted\"", [])
    assert "Ride &amp; &lt;Test&gt; &quot;quoted&quot;" in gpx_text
    assert "<Test>" not in gpx_text


def test_omits_extensions_block_when_no_hr_or_cadence():
    points = [RecordedTrackPoint(timestamp=1_700_000_000.0, lat=1.0, lon=1.0, elevation_meters=None, heart_rate_bpm=None, cadence_rpm=None)]
    gpx_text = write("No HR", points)
    assert "extensions" not in gpx_text
    assert "gpxtpx" not in gpx_text.split("xmlns:gpxtpx")[1]  # not used anywhere past the namespace decl


def test_includes_hr_and_cadence_extension_when_present():
    points = [
        RecordedTrackPoint(
            timestamp=1_700_000_000.0, lat=1.0, lon=1.0, elevation_meters=None, heart_rate_bpm=150, cadence_rpm=85.6
        )
    ]
    gpx_text = write("With HR", points)
    assert "<gpxtpx:hr>150</gpxtpx:hr>" in gpx_text
    assert "<gpxtpx:cad>86</gpxtpx:cad>" in gpx_text  # rounded
