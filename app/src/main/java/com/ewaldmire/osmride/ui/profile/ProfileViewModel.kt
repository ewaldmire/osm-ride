package com.ewaldmire.osmride.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.weight.WaistEntry
import com.ewaldmire.osmride.weight.WeightEntry
import kotlinx.coroutines.flow.StateFlow

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val weightRepository = app.weightRepository
    private val waistRepository = app.waistRepository

    /** Newest first - the profile summary only ever needs .firstOrNull(). */
    val weightEntries: StateFlow<List<WeightEntry>> = weightRepository.entries

    /** Newest first - the profile summary only ever needs .firstOrNull(). */
    val waistEntries: StateFlow<List<WaistEntry>> = waistRepository.entries
}
