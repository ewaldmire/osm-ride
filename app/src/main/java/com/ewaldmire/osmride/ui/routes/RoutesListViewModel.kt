package com.ewaldmire.osmride.ui.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.route.RouteSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoutesListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).routeRepository

    val routes: StateFlow<List<RouteSummary>> = repository.routes

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
}
