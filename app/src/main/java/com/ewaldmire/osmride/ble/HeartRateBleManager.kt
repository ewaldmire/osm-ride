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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connects to a standard BLE Heart Rate Service (0x180D) device, e.g. a chest strap. */
@SuppressLint("MissingPermission")
class HeartRateBleManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 16)
    val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    /** Name of the currently connecting/connected device, for a "what's connected" display. */
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var gatt: BluetoothGatt? = null

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
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.HEART_RATE_SERVICE)).build(),
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
        _connectedDeviceName.value = device.name ?: address
        _connectionState.value = BleConnectionState.CONNECTING
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectedDeviceName.value = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    g.close()
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val hrChar = g.getService(BleConstants.HEART_RATE_SERVICE)
                ?.getCharacteristic(BleConstants.HEART_RATE_MEASUREMENT)
            if (hrChar == null) {
                disconnect()
                return
            }
            g.setCharacteristicNotification(hrChar, true)
            val descriptor = hrChar.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            _connectionState.value = BleConnectionState.CONNECTED
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            val bpm = parseHeartRateMeasurement(data) ?: return
            _samples.tryEmit(HeartRateSample(bpm))
        }
    }

    private fun parseHeartRateMeasurement(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = readUInt8(data, 0)
        val isUInt16 = flags and 0x01 != 0
        return if (isUInt16) {
            if (data.size < 3) null else readUInt16LE(data, 1)
        } else {
            if (data.size < 2) null else readUInt8(data, 1)
        }
    }
}
