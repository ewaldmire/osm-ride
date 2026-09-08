package com.ewaldmire.osmride.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.weight.WeightEntry
import kotlinx.coroutines.flow.StateFlow

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val weightRepository = (application as OsmRideApp).weightRepository

    /** Newest first - the profile summary only ever needs .firstOrNull(). */
    val weightEntries: StateFlow<List<WeightEntry>> = weightRepository.entries
}
