package com.ewaldmire.osmride.ui.weight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.util.Units
import com.ewaldmire.osmride.weight.WeightEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WeightViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).weightRepository

    val entries: StateFlow<List<WeightEntry>> = repository.entries

    fun addEntry(weightLbs: Double) {
        viewModelScope.launch { repository.addEntry(Units.lbsToKg(weightLbs)) }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch { repository.deleteEntry(id) }
    }
}
