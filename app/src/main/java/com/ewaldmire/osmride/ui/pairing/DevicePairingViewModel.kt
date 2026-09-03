package com.ewaldmire.osmride.ui.pairing

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ble.ScannedDevice
import kotlinx.coroutines.flow.StateFlow

class DevicePairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val trainerManager = app.trainerBleManager
    private val hrManager = app.heartRateBleManager
    private val prefs = application.getSharedPreferences("ble_devices", Context.MODE_PRIVATE)

    val trainerState: StateFlow<BleConnectionState> = trainerManager.connectionState
    val trainerDevices: StateFlow<List<ScannedDevice>> = trainerManager.scannedDevices
    val hrState: StateFlow<BleConnectionState> = hrManager.connectionState
    val hrDevices: StateFlow<List<ScannedDevice>> = hrManager.scannedDevices

    init {
        prefs.getString(KEY_TRAINER, null)?.let { trainerManager.connect(it) }
        prefs.getString(KEY_HR, null)?.let { hrManager.connect(it) }
    }

    fun startTrainerScan() = trainerManager.startScan()
    fun stopTrainerScan() = trainerManager.stopScan()

    fun connectTrainer(address: String) {
        trainerManager.connect(address)
        prefs.edit().putString(KEY_TRAINER, address).apply()
    }

    fun disconnectTrainer() {
        trainerManager.disconnect()
        prefs.edit().remove(KEY_TRAINER).apply()
    }

    fun startHrScan() = hrManager.startScan()
    fun stopHrScan() = hrManager.stopScan()

    fun connectHr(address: String) {
        hrManager.connect(address)
        prefs.edit().putString(KEY_HR, address).apply()
    }

    fun disconnectHr() {
        hrManager.disconnect()
        prefs.edit().remove(KEY_HR).apply()
    }

    private companion object {
        const val KEY_TRAINER = "trainer_address"
        const val KEY_HR = "hr_address"
    }
}
