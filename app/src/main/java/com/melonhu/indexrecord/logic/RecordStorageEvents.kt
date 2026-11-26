package com.melonhu.indexrecord.logic

import android.util.Log
import com.melonhu.indexrecord.data.model.UniversalRecord
import java.util.concurrent.CopyOnWriteArrayList

internal interface RecordStorageObserver {
    fun onRecordsPersisted(records: List<UniversalRecord>) {}
    fun onDatabaseCleared() {}
}

/**
 * In-process storage lifecycle events emitted after committed ObjectBox writes.
 * Downstream modules (e.g., search indexing) subscribe at startup so persistence never depends on them.
 */
internal object RecordStorageEvents {
    private const val TAG = "RecordStorageEvents"
    private val observers = CopyOnWriteArrayList<RecordStorageObserver>()

    fun registerObserver(observer: RecordStorageObserver) {
        observers.addIfAbsent(observer)
    }

    fun unregisterObserver(observer: RecordStorageObserver) {
        observers.remove(observer)
    }

    fun notifyRecordsPersisted(records: List<UniversalRecord>) {
        if (records.isEmpty()) return
        for (observer in observers) {
            try {
                observer.onRecordsPersisted(records)
            } catch (t: Throwable) {
                runCatching { Log.e(TAG, "Observer failed in onRecordsPersisted", t) }
            }
        }
    }

    fun notifyDatabaseCleared() {
        for (observer in observers) {
            try {
                observer.onDatabaseCleared()
            } catch (t: Throwable) {
                runCatching { Log.e(TAG, "Observer failed in onDatabaseCleared", t) }
            }
        }
    }
}
