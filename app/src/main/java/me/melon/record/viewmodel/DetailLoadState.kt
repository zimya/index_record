package me.melon.record.viewmodel

/** Loading describes the whole snapshot, not individual page regions. */
internal sealed interface DetailLoadState {
    data object Loading : DetailLoadState
    data object Ready : DetailLoadState
    data class Failed(val message: String) : DetailLoadState
}
