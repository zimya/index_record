package me.melon.record.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.melon.record.data.model.UniversalRecord

/** Each visit owns its reading context, even when the same record occurs twice in the stack. */
class DetailNavigationEntry internal constructor(val key: Long, val record: UniversalRecord) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    internal var compositionCount = 0
    internal var removed = false
}

class DetailNavigationViewModel : ViewModel() {
    enum class Action { PUSH, POP }

    private val stack = mutableStateListOf<DetailNavigationEntry>()
    val entries: List<DetailNavigationEntry> get() = stack
    private var nextKey = 0L
    private val exitingEntries = mutableSetOf<DetailNavigationEntry>()
    var lastAction by mutableStateOf(Action.PUSH)
        private set

    fun push(record: UniversalRecord) {
        val current = stack.lastOrNull()?.record
        if (current != null && current.platform == record.platform &&
            current.recordType == record.recordType && current.platformId == record.platformId) return
        lastAction = Action.PUSH
        stack.add(DetailNavigationEntry(nextKey++, record))
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        lastAction = Action.POP
        val entry = stack.removeAt(stack.lastIndex)
        entry.removed = true
        if (entry.compositionCount == 0) entry.viewModelStore.clear() else exitingEntries.add(entry)
        return true
    }

    fun attach(entry: DetailNavigationEntry) {
        entry.compositionCount++
    }

    fun detach(entry: DetailNavigationEntry) {
        entry.compositionCount--
        if (entry.removed && entry.compositionCount == 0) {
            exitingEntries.remove(entry)
            entry.viewModelStore.clear()
        }
    }

    override fun onCleared() {
        (stack + exitingEntries).forEach { it.viewModelStore.clear() }
        exitingEntries.clear()
    }
}
