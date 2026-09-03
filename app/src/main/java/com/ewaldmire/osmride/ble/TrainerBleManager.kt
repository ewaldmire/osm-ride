package com.ewaldmire.osmride.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class TrainerProtocol { FTMS, CSC }

/**
 * Connects to a BLE smart trainer and streams parsed speed/cadence/power/distance samples.
 *
 * All BLE calls here assume the caller has already obtained BLUETOOTH_SCAN/BLUETOOTH_CONNECT
 * (runtime permission flow lives in the UI layer before any of these methods are invoked), so
 * permission checks are intentionally not duplicated here.
 */
@SuppressLint("MissingPermission")
class TrainerBleManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _samples = MutableSharedFlow<TrainerSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<TrainerSample> = _samples.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var protocol: TrainerProtocol? = null

    private val simulationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simulationJob: Job? = null

    // CSC-fallback rollover tracking state, reset per-connection.
    private var previousWheelRevs: Long? = null
    private var previousWheelEventTime: Int? = null
    private var previousCrankRevs: Int? = null
    private var previousCrankEventTime: Int? = null
    private var cscCumulativeDistanceMeters = 0.0

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            val current = _scannedDevices.value
            if (current.none { it.address == device.address }) {
                _scannedDevices.value = current + ScannedDevice(name, device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.FTMS_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.CSC_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        previousWheelRevs = null
        previousWheelEventTime = null
        previousCrankRevs = null
        previousCrankEventTime = null
        cscCumulativeDistanceMeters = 0.0
        _connectionState.value = BleConnectionState.CONNECTING
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    fun disconnect() {
        simulationJob?.cancel()
        simulationJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        protocol = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    /**
     * Testing helper: feeds synthetic speed/cadence/power samples on a 1Hz timer, with no real
     * BLE device involved, so the ride screen and avatar movement can be exercised without
     * trainer hardware. Distance is deliberately left null so the same speed-integration path
     * used for real CSC-only trainers gets exercised too.
     */
    fun startSimulation() {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTED
        simulationJob = simulationScope.launch {
            var t = 0.0
            while (isActive) {
                val speedMps = 5.5 + sin(t / 20.0) * 1.5 // ~9-15.5 mph, slowly varying
                val cadenceRpm = 82.0 + sin(t / 15.0) * 6.0
                val powerWatts = 150 + (sin(t / 12.0) * 30.0).roundToInt()
                _samples.tryEmit(
                    TrainerSample(
                        speedMetersPerSecond = speedMps,
                        cadenceRpm = cadenceRpm,
                        powerWatts = powerWatts,
                        totalDistanceMeters = null,
                    ),
                )
                t += 1.0
                delay(1000)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    g.close()
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ftmsChar = g.getService(BleConstants.FTMS_SERVICE)
                ?.getCharacteristic(BleConstants.INDOOR_BIKE_DATA)
            val cscChar = g.getService(BleConstants.CSC_SERVICE)
                ?.getCharacteristic(BleConstants.CSC_MEASUREMENT)

            val target = ftmsChar ?: cscChar
            if (target == null) {
                disconnect()
                return
            }
            protocol = if (ftmsChar != null) TrainerProtocol.FTMS else TrainerProtocol.CSC
            enableNotifications(g, target)
            _connectionState.value = BleConnectionState.CONNECTED
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            val sample = when (protocol) {
                TrainerProtocol.FTMS -> parseIndoorBikeData(data)
                TrainerProtocol.CSC -> parseCscMeasurement(data)
                null -> null
            }
            if (sample != null) {
                _samples.tryEmit(sample)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            ?: return
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(descriptor)
    }

    private fun parseIndoorBikeData(data: ByteArray): TrainerSample? {
        if (data.size < 2) return null
        var idx = 0
        val flags = readUInt16LE(data, idx); idx += 2

        var speed: Double? = null
        if (flags and 0x0001 == 0 && idx + 2 <= data.size) {
            speed = readUInt16LE(data, idx) * 0.01 / 3.6 // 0.01 km/h -> m/s
            idx += 2
        }
        if (flags and 0x0002 != 0) idx += 2 // average speed, unused
        var cadence: Double? = null
        if (flags and 0x0004 != 0 && idx + 2 <= data.size) {
            cadence = readUInt16LE(data, idx) * 0.5 // 0.5 rpm resolution
            idx += 2
        }
        if (flags and 0x0008 != 0) idx += 2 // average cadence, unused
        var totalDistance: Double? = null
        if (flags and 0x0010 != 0 && idx + 3 <= data.size) {
            totalDistance = readUInt24LE(data, idx).toDouble()
            idx += 3
        }
        if (flags and 0x0020 != 0) idx += 2 // resistance level, unused
        var power: Int? = null
        if (flags and 0x0040 != 0 && idx + 2 <= data.size) {
            power = readSInt16LE(data, idx)
            idx += 2
        }

        return TrainerSample(
            speedMetersPerSecond = speed,
            cadenceRpm = cadence,
            powerWatts = power,
            totalDistanceMeters = totalDistance,
        )
    }

    private fun parseCscMeasurement(data: ByteArray): TrainerSample? {
        if (data.isEmpty()) return null
        val flags = readUInt8(data, 0)
        var idx = 1
        var speed: Double? = null
        var totalDistance: Double? = null

        if (flags and 0x01 != 0 && idx + 6 <= data.size) {
            val cumulativeWheelRevs = readUInt32LE(data, idx)
            val lastWheelEventTime = readUInt16LE(data, idx + 4)
            idx += 6

            val prevRevs = previousWheelRevs
            val prevTime = previousWheelEventTime
            if (prevRevs != null && prevTime != null) {
                val revDelta = (cumulativeWheelRevs - prevRevs).let { if (it < 0) it + 0x100000000L else it }
                val timeDeltaTicks = (lastWheelEventTime - prevTime).let { if (it < 0) it + 0x10000 else it }
                if (timeDeltaTicks > 0) {
                    val timeDeltaSeconds = timeDeltaTicks / 1024.0
                    val distanceDelta = revDelta * BleConstants.DEFAULT_WHEEL_CIRCUMFERENCE_METERS
                    speed = distanceDelta / timeDeltaSeconds
                    cscCumulativeDistanceMeters += distanceDelta
                    totalDistance = cscCumulativeDistanceMeters
                }
            }
            previousWheelRevs = cumulativeWheelRevs
            previousWheelEventTime = lastWheelEventTime
        }

        var cadence: Double? = null
        if (flags and 0x02 != 0 && idx + 4 <= data.size) {
            val cumulativeCrankRevs = readUInt16LE(data, idx)
            val lastCrankEventTime = readUInt16LE(data, idx + 2)
            idx += 4

            val prevRevs = previousCrankRevs
            val prevTime = previousCrankEventTime
            if (prevRevs != null && prevTime != null) {
                val revDelta = (cumulativeCrankRevs - prevRevs).let { if (it < 0) it + 0x10000 else it }
                val timeDeltaTicks = (lastCrankEventTime - prevTime).let { if (it < 0) it + 0x10000 else it }
                if (timeDeltaTicks > 0) {
                    val timeDeltaSeconds = timeDeltaTicks / 1024.0
                    cadence = revDelta * 60.0 / timeDeltaSeconds
                }
            }
            previousCrankRevs = cumulativeCrankRevs
            previousCrankEventTime = lastCrankEventTime
        }

        if (speed == null && cadence == null) return null
        return TrainerSample(
            speedMetersPerSecond = speed,
            cadenceRpm = cadence,
            powerWatts = null,
            totalDistanceMeters = totalDistance,
        )
    }
}
