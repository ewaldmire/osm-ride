"""A coarse activity classification for Profile's Activities feed - just enough for a sensible
label/icon, not a full mirror of FIT's ~80-value sport enum. Live-recorded indoor rides are always
CYCLING (the trainer only supports cycling); imported .fit files get their type from the file's
own `sport` field (see from_fit_sport) since outdoor imports can be anything a bike computer/GPS
watch records - a kayaking trip imported from the same device shouldn't be mislabeled as a bike
ride.

Plain string constants rather than a formal Enum - RideRecord is a plain dataclass persisted via
json.dumps/RideRecord(**entry), and a string round-trips through that for free.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/ActivityType.kt, though fitparse already
resolves the FIT `sport` field to its name string (see fit_parser.py) rather than leaving Linux to
decode the raw enum int by hand the way the Android side has to.
"""

from __future__ import annotations

CYCLING = "cycling"
RUNNING = "running"
WALKING = "walking"
HIKING = "hiking"
SWIMMING = "swimming"
KAYAKING = "kayaking"
ROWING = "rowing"
OTHER = "other"

_FIT_SPORT_NAME_MAP = {
    "running": RUNNING,
    "cycling": CYCLING,
    "e_biking": CYCLING,
    "swimming": SWIMMING,
    "walking": WALKING,
    "rowing": ROWING,
    "hiking": HIKING,
    "paddling": KAYAKING,
    "stand_up_paddleboarding": KAYAKING,
    "kayaking": KAYAKING,
}


def from_fit_sport(sport_name: str | None) -> str:
    """[sport_name] is fitparse's already-resolved name for the FIT session message's `sport`
    field (e.g. "cycling", "kayaking") - not a raw int. Anything outside the handful mapped above
    (golf, skiing, sailing, ...) falls back to OTHER rather than trying to cover all ~80 FIT sport
    values with a dedicated case each."""
    if sport_name is None:
        return OTHER
    return _FIT_SPORT_NAME_MAP.get(sport_name, OTHER)
