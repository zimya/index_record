package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

@Composable
internal fun rememberRedditRecordDetailData(record: UniversalRecord): RecordDetailData {
    val data = remember(record.platformId) { RecordDetailData() }
    LaunchedEffect(record.platformId) {
        withContext(Dispatchers.IO) {
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)
            val query = box.query()
                .equal(UniversalRecord_.platform, "reddit", QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
            val updates = Channel<Unit>(Channel.CONFLATED)
            try {
                // Query subscription is Reddit-only; conflate bursts and query current data off the UI thread.
                val subscription = query.subscribe().observer { updates.trySend(Unit) }
                try {
                    updates.trySend(Unit)
                    for (update in updates) {
                        val records = query.find()
                        val current = records.firstOrNull { it.platformId == record.platformId } ?: record
                        val comments = selectRedditComments(current, records)
                        val parent = if (current.parentId.isNotBlank()) {
                            records.firstOrNull { it.platformId == current.parentId }
                        } else null
                        withContext(Dispatchers.Main) {
                            data.refreshedRecord = current
                            data.allComments = comments
                            data.relatedRecord = parent
                            data.commentsLoaded = true
                            data.relatedLoaded = true
                        }
                    }
                } finally {
                    subscription.cancel()
                }
            } finally {
                updates.close()
                query.close()
            }
        }
    }
    return data
}
