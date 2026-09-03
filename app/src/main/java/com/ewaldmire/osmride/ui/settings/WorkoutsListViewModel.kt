package com.ewaldmire.osmride.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkoutsListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).workoutRepository

    val workouts: StateFlow<List<Workout>> = repository.workouts

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importWorkout(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val ftpWatts = SettingsPrefs.getFtpWatts(getApplication())
            repository.importWorkout(uri, displayName, ftpWatts)
                .onFailure { _importError.value = it.message ?: "Could not import that workout file" }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun renameWorkout(id: String, name: String) {
        viewModelScope.launch { repository.renameWorkout(id, name) }
    }

    fun deleteWorkout(id: String) {
        viewModelScope.launch { repository.deleteWorkout(id) }
    }
}
