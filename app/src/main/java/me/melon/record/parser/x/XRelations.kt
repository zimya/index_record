package me.melon.record.parser.x

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.Box
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.parser.x.XUtils.safeLong
import me.melon.record.parser.x.XUtils.safeString

object XRelations {

    private val gson = Gson()

    /**
     * 合并推文的额外元数据（保留更高的互动量、补充字段等）
     */
    fun mergeTweetExtra(existing: String, incoming: String): String {
        val oldObj = try { JsonParser.parseString(existing).asJsonObject } catch (_: Exception) { JsonObject() }
        val newObj = try { JsonParser.parseString(incoming).asJsonObject } catch (_: Exception) { JsonObject() }

        for ((key, value) in oldObj.entrySet()) {
            if (!newObj.has(key)) {
                newObj.add(key, value)
            } else {
                // 对于计数类字段，取最大值
                if (key.endsWith("_count")) {
                    val oldVal = oldObj.safeLong(key)
                    val newVal = newObj.safeLong(key)
                    if (oldVal > newVal) {
                        newObj.addProperty(key, oldVal)
                    }
                }
            }
        }

        return gson.toJson(newObj)
    }

    /**
     * 维护推文与回复评论之间的父子关联
     */
    fun linkTweetRelations(recordBox: Box<UniversalRecord>, record: UniversalRecord) {
        if (record.platform != "twitter") return

        if (record.recordType == "comment") {
            // 如果回复的父级暂时指向了不存在的 comment 或 tweet，尝试校正
            val targetParentId = record.parentId
            if (targetParentId.isNotBlank()) {
                val exists = recordBox.query()
                    .equal(UniversalRecord_.platformId, targetParentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .findFirst()

                if (exists == null) {
                    val rawTargetId = if (targetParentId.startsWith("twitter_comment_")) {
                        targetParentId.removePrefix("twitter_comment_")
                    } else if (targetParentId.startsWith("twitter_tweet_")) {
                        targetParentId.removePrefix("twitter_tweet_")
                    } else ""

                    if (rawTargetId.isNotBlank()) {
                        val altPlatformId = if (targetParentId.startsWith("twitter_comment_")) {
                            "twitter_tweet_$rawTargetId"
                        } else {
                            "twitter_comment_$rawTargetId"
                        }
                        val altExists = recordBox.query()
                            .equal(UniversalRecord_.platformId, altPlatformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .build()
                            .findFirst()

                        if (altExists != null) {
                            record.parentId = altExists.platformId
                            recordBox.put(record)
                        }
                    }
                }
            }
        } else if (record.recordType == "tweet") {
            val rawTweetId = record.platformId.removePrefix("twitter_tweet_")
            if (rawTweetId.isNotBlank()) {
                // 查找之前可能以 twitter_comment_$rawTweetId 为 parentId 的孤儿回复
                val orphanComments = recordBox.query()
                    .equal(UniversalRecord_.platform, "twitter", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.parentId, "twitter_comment_$rawTweetId", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .find()

                if (orphanComments.isNotEmpty()) {
                    for (c in orphanComments) {
                        c.parentId = record.platformId
                    }
                    recordBox.put(orphanComments)
                }
            }
        }
    }
}
