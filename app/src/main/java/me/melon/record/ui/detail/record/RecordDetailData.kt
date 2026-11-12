package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord_

internal class RecordDetailData {
    var allComments by mutableStateOf<List<UniversalRecord>>(emptyList())
    var relatedRecord by mutableStateOf<UniversalRecord?>(null)
    var commentsLoaded by mutableStateOf(false)
    var relatedLoaded by mutableStateOf(false)
}

@Composable
internal fun rememberRecordDetailData(record: UniversalRecord): RecordDetailData {
    val data = remember { RecordDetailData() }
    with(data) {
        LaunchedEffect(record.platformId) {
            withContext(Dispatchers.IO) {
                val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

                val altId = if (record.platform == "twitter") {
                    if (record.platformId.startsWith("twitter_tweet_")) {
                        "twitter_comment_" + record.platformId.removePrefix("twitter_tweet_")
                    } else if (record.platformId.startsWith("twitter_comment_")) {
                        "twitter_tweet_" + record.platformId.removePrefix("twitter_comment_")
                    } else ""
                } else ""

                val rootComments = if (altId.isNotBlank()) {
                    box.query()
                        .`in`(UniversalRecord_.parentId, arrayOf(record.platformId, altId), io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .find()
                } else {
                    box.query()
                        .equal(UniversalRecord_.parentId, record.platformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .find()
                }

                val all = rootComments.toMutableList()
                val seen = rootComments.map { it.platformId }.toMutableSet().apply { add(record.platformId) }
                var frontier = rootComments.map { it.platformId }

                while (frontier.isNotEmpty()) {
                    val parentIds = frontier.toTypedArray()
                    val children = box.query()
                        .`in`(UniversalRecord_.parentId, parentIds, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .find()

                    val newChildren = children.filter { seen.add(it.platformId) }
                    if (newChildren.isEmpty()) break
                    all.addAll(newChildren)
                    frontier = newChildren.map { it.platformId }
                }

                allComments = all
                commentsLoaded = true
            }
        }

        LaunchedEffect(record.platformId, record.parentId) {
            withContext(Dispatchers.IO) {
                val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

                fun queryRelated() {
                    if (record.platform == "zhihu" && record.recordType == "answer" && record.parentId.startsWith("zhihu_question_")) {
                        relatedRecord = box.query()
                            .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .build()
                            .findFirst()
                    } else if (record.platform in setOf("weibo", "threads") && record.parentId.isNotBlank()) {
                        relatedRecord = box.query()
                            .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .equal(UniversalRecord_.platform, record.platform, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .build()
                            .findFirst()
                    } else if (record.platform == "douban" && record.parentId.startsWith("douban_")) {
                        val found = box.query()
                            .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .build()
                            .findFirst()
                        if (found != null) {
                            relatedRecord = found
                        } else {
                            // 若尚未捕获条目本体，根据记录中的 subject 元数据构造一个条目引用，点击后可查看聚合短评/长评
                            try {
                                val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
                                val sId = if (meta.has("subject_id") && !meta.get("subject_id").isJsonNull) meta.get("subject_id").asString else ""
                                val sTitle = if (meta.has("subject_title") && !meta.get("subject_title").isJsonNull) meta.get("subject_title").asString else ""
                                val sType = if (meta.has("subject_type") && !meta.get("subject_type").isJsonNull) meta.get("subject_type").asString else "movie"
                                if (sId.isNotBlank() && sTitle.isNotBlank()) {
                                    relatedRecord = UniversalRecord(
                                        platform = "douban",
                                        recordType = "subject",
                                        sourceApp = record.sourceApp,
                                        platformId = record.parentId,
                                        title = sTitle,
                                        content = sTitle,
                                        extraMetadata = record.extraMetadata
                                    )
                                } else {
                                    relatedRecord = null
                                }
                            } catch (_: Exception) {
                                relatedRecord = null
                            }
                        }
                    } else {
                        relatedRecord = null
                    }
                }

                queryRelated()
                relatedLoaded = true

                if (record.parentId.isNotBlank()) {
                    val sub = box.query()
                        .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .subscribe()
                        .observer {
                            queryRelated()
                        }
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        sub.cancel()
                    }
                }
            }
        }
    }
    return data
}
