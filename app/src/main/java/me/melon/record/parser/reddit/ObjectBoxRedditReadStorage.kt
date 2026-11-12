package me.melon.record.parser.reddit

import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.RedditPendingRecord_

/** Mutations share the caller's transaction with publishing records and deleting RawData. */
class ObjectBoxRedditReadStorage(private val box: Box<RedditPendingRecord>) : RedditReadBuffer.Storage {
    override fun find(platformId: String): RedditPendingRecord? = box.query()
        .equal(RedditPendingRecord_.platformId, platformId, StringOrder.CASE_SENSITIVE)
        .build().use { it.findFirst() }

    override fun forPost(postId: String): List<RedditPendingRecord> = box.query()
        .equal(RedditPendingRecord_.postId, postId, StringOrder.CASE_SENSITIVE)
        .build().use { it.find() }

    override fun put(row: RedditPendingRecord) { box.put(row) }
    override fun remove(row: RedditPendingRecord) { box.remove(row.id) }

    override fun pending(): List<RedditPendingRecord> = box.query()
        .equal(RedditPendingRecord_.confirmed, false)
        .build().use { it.find() }
}
