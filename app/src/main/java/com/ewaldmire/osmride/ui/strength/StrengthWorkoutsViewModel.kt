package com.ewaldmire.osmride.ui.strength

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Read-only from the user's perspective - no add/edit here, only import (see
 * StrengthWorkoutImport) and delete. */
class StrengthWorkoutsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).strengthWorkoutRepository

    /** Newest first. */
    val entries: StateFlow<List<StrengthWorkoutEntry>> = repository.entries

    fun deleteEntry(id: String) {
        viewModelScope.launch { repository.deleteEntry(id) }
    }
}
