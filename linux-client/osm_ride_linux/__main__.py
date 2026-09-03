"""Lets the app run as `python3 -m osm_ride_linux` from the linux-client directory."""

import sys

from .ui.app import main

if __name__ == "__main__":
    sys.exit(main())
