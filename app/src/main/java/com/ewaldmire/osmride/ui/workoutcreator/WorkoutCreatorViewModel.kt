package com.ewaldmire.osmride.ui.workoutcreator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.Workout
import com.ewaldmire.osmride.ride.WorkoutSegment
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BlockType { STEADY, RAMP, FREE_RIDE }

/** One editable block in the builder - converted to a [WorkoutSegment] (with computed cumulative
 * start/end seconds) only when previewing or saving. [watts] is used for [BlockType.STEADY],
 * [startWatts]/[endWatts] for [BlockType.RAMP]; [BlockType.FREE_RIDE] uses neither. */
data class WorkoutBlockDraft(
    val id: String = UUID.randomUUID().toString(),
    val durationSeconds: Long = 300,
    val type: BlockType = BlockType.STEADY,
    val watts: Int = 150,
    val startWatts: Int = 150,
    val endWatts: Int = 250,
)

/** Drives the block-based workout creator: a sequential list of duration+power blocks, converted
 * to a flat [WorkoutSegment] list (with running start/end seconds) on save. */
class WorkoutCreatorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).workoutRepository

    private var existingId: String? = null

    private val _name = MutableStateFlow("New Workout")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _blocks = MutableStateFlow<List<WorkoutBlockDraft>>(emptyList())
    val blocks: StateFlow<List<WorkoutBlockDraft>> = _blocks.asStateFlow()

    val previewWorkout: StateFlow<Workout> = combine(_name, _blocks) { name, blocks ->
        buildWorkout(existingId ?: "preview", name, blocks)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, buildWorkout("preview", _name.value, emptyList()))

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    fun loadForEdit(workoutId: String) {
        if (existingId == workoutId) return
        existingId = workoutId
        val workout = repository.getWorkout(workoutId) ?: return
        _name.value = workout.name
        _blocks.value = workout.segments.map { it.toDraft() }
    }

    fun updateName(newName: String) {
        _name.value = newName
    }

    fun addBlock(draft: WorkoutBlockDraft) {
        _blocks.value = _blocks.value + draft
    }

    fun updateBlock(draft: WorkoutBlockDraft) {
        _blocks.value = _blocks.value.map { if (it.id == draft.id) draft else it }
    }

    fun removeBlock(id: String) {
        _blocks.value = _blocks.value.filterNot { it.id == id }
    }

    fun moveBlock(id: String, delta: Int) {
        val list = _blocks.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val newIndex = (index + delta).coerceIn(0, list.lastIndex)
        if (newIndex == index) return
        list.add(newIndex, list.removeAt(index))
        _blocks.value = list
    }

    fun clearError() {
        _error.value = null
    }

    fun save() {
        val currentBlocks = _blocks.value
        if (currentBlocks.isEmpty()) {
            _error.value = "Add at least one block first"
            return
        }
        viewModelScope.launch {
            repository.saveCreatedWorkout(existingId, _name.value, toSegments(currentBlocks))
                .onSuccess { workout -> _saved.value = workout.id }
                .onFailure { _error.value = it.message ?: "Couldn't save workout" }
        }
    }
}

private fun buildWorkout(id: String, name: String, blocks: List<WorkoutBlockDraft>): Workout {
    val segments = toSegments(blocks)
    return Workout(
        id = id,
        name = name,
        segments = segments,
        totalDurationSeconds = segments.maxOfOrNull { it.endSeconds } ?: 0,
    )
}

private fun toSegments(blocks: List<WorkoutBlockDraft>): List<WorkoutSegment> {
    var cursor = 0L
    return blocks.map { block ->
        val start = cursor
        val end = start + block.durationSeconds
        cursor = end
        when (block.type) {
            BlockType.STEADY -> WorkoutSegment(start, end, block.watts, block.watts)
            BlockType.RAMP -> WorkoutSegment(start, end, block.startWatts, block.endWatts)
            BlockType.FREE_RIDE -> WorkoutSegment(start, end, null, null)
        }
    }
}

private fun WorkoutSegment.toDraft(): WorkoutBlockDraft {
    val duration = (endSeconds - startSeconds).coerceAtLeast(1)
    val start = startWatts
    val end = endWatts
    return when {
        start == null || end == null -> WorkoutBlockDraft(durationSeconds = duration, type = BlockType.FREE_RIDE)
        start == end -> WorkoutBlockDraft(durationSeconds = duration, type = BlockType.STEADY, watts = start)
        else -> WorkoutBlockDraft(
            durationSeconds = duration,
            type = BlockType.RAMP,
            startWatts = start,
            endWatts = end,
        )
    }
}
