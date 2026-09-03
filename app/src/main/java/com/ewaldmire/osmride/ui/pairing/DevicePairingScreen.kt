package com.ewaldmire.osmride.ui.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ble.ScannedDevice
import com.ewaldmire.osmride.util.Permissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    onDone: () -> Unit,
    viewModel: DevicePairingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val trainerState by viewModel.trainerState.collectAsState()
    val trainerDevices by viewModel.trainerDevices.collectAsState()
    val hrState by viewModel.hrState.collectAsState()
    val hrDevices by viewModel.hrDevices.collectAsState()

    var pendingScan by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) pendingScan?.invoke()
        pendingScan = null
    }

    fun scanWithPermission(scan: () -> Unit) {
        val permissions = Permissions.blePermissions()
        if (Permissions.hasAll(context, permissions)) {
            scan()
        } else {
            pendingScan = scan
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Devices") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            DeviceSection(
                title = "Smart Trainer",
                state = trainerState,
                devices = trainerDevices,
                onScan = { scanWithPermission { viewModel.startTrainerScan() } },
                onStopScan = viewModel::stopTrainerScan,
                onConnect = viewModel::connectTrainer,
                onDisconnect = viewModel::disconnectTrainer,
                onSimulate = viewModel::simulateTrainer,
            )
            DeviceSection(
                title = "Heart Rate Monitor",
                state = hrState,
                devices = hrDevices,
                onScan = { scanWithPermission { viewModel.startHrScan() } },
                onStopScan = viewModel::stopHrScan,
                onConnect = viewModel::connectHr,
                onDisconnect = viewModel::disconnectHr,
            )
        }
    }
}

@Composable
private fun DeviceSection(
    title: String,
    state: BleConnectionState,
    devices: List<ScannedDevice>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSimulate: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(state.name, style = MaterialTheme.typography.labelMedium)
        }

        when (state) {
            BleConnectionState.CONNECTED -> {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Disconnect")
                }
            }
            BleConnectionState.SCANNING -> {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Button(onClick = onStopScan) { Text("Stop Scan") }
                }
            }
            else -> {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onScan) { Text("Scan") }
                    if (onSimulate != null) {
                        TextButton(onClick = onSimulate) { Text("Simulate for testing") }
                    }
                }
            }
        }

        if (devices.isNotEmpty() && state != BleConnectionState.CONNECTED) {
            LazyColumn(
                contentPadding = PaddingValues(top = 8.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    Card(
                        onClick = { onConnect(device.address) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
