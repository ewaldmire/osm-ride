package com.ewaldmire.osmride.ui.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.route.RouteSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoutesListViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val repository = app.routeRepository

    val routes: StateFlow<List<RouteSummary>> = repository.routes

    /** Non-null while a ride is in progress (or just finished but not yet saved), for a
     * "resume ride" banner and to stop the user from starting a second concurrent ride. */
    val activeRideEngine: StateFlow<RideEngine?> = app.currentRideEngineFlow

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importGpx(uri: Uri, displayNameHint: String?) {
        viewModelScope.launch {
            repository.importGpx(uri, displayNameHint)
                .onFailure { _importError.value = it.message ?: "Could not import that GPX file" }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch { repository.deleteRoute(id) }
    }

    fun renameRoute(id: String, name: String) {
        viewModelScope.launch { repository.renameRoute(id, name) }
    }
}
