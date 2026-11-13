package me.melon.record.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** Restarts only the observer, leaving snapshot reducers alive to retain their last good data. */
internal class DetailRetry<T>(private val observe: () -> Flow<Result<T>>) {
    private val requests = MutableStateFlow(0L)
    private val running = MutableStateFlow(false)
    val isRetrying: StateFlow<Boolean> = running.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshots: Flow<Result<T>> = requests.flatMapLatest {
        // Construct inside the flow: subscribing can fail before an observer is returned.
        flow { emitAll(observe()) }
            .onStart { running.value = true }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(Result.failure(failure))
            }
            .onEach { running.value = false }
            .onCompletion { running.value = false }
    }

    fun retry() {
        if (running.value) return
        running.value = true
        requests.update { it + 1 }
    }
}
