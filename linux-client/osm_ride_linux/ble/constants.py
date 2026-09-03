"""Bluetooth SIG standard service/characteristic UUIDs used by trainers and HR monitors.

Mirrors app/src/main/java/com/ewaldmire/osmride/ble/BleConstants.kt exactly - same short-UUID
values expanded to the standard 128-bit Bluetooth Base UUID form bleak expects as strings.
"""


def _sig(short_uuid: int) -> str:
    return f"{short_uuid:08x}-0000-1000-8000-00805f9b34fb"


# Fitness Machine Service (FTMS)
FTMS_SERVICE = _sig(0x1826)
INDOOR_BIKE_DATA = _sig(0x2AD2)
# Write-with-response + indicate. Used to request control and send simulated grade / target power.
FITNESS_MACHINE_CONTROL_POINT = _sig(0x2AD9)

# Cycling Speed and Cadence (CSC) - fallback for trainers without FTMS.
CSC_SERVICE = _sig(0x1816)
CSC_MEASUREMENT = _sig(0x2A5B)

# Heart Rate Service (HRS)
HEART_RATE_SERVICE = _sig(0x180D)
HEART_RATE_MEASUREMENT = _sig(0x2A37)

# Default 700x25c road tire circumference, used only for the CSC-fallback distance calc.
DEFAULT_WHEEL_CIRCUMFERENCE_METERS = 2.105

# FTMS Control Point op codes (Bluetooth SIG Fitness Machine Service spec).
OP_REQUEST_CONTROL = 0x00
OP_SET_TARGET_POWER = 0x05
OP_SET_INDOOR_BIKE_SIMULATION_PARAMETERS = 0x11
OP_RESPONSE_CODE = 0x80
RESULT_SUCCESS = 0x01
