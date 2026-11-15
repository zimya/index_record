package com.melonhu.indexrecord.parser.x

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.x.XUtils.safeJsonObject
import com.melonhu.indexrecord.parser.x.XUtils.safeString

object XTimelineParser {

    /**
     * 解析任意包含 Twitter/X 推文流或推文详情的 JSON 数据
     */
    fun parse(raw: RawData): List<UniversalRecord> {
        val rootElement = try {
            JsonParser.parseString(raw.json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!rootElement.isJsonObject) return emptyList()
        val rootObj = rootElement.asJsonObject

        val recordsMap = LinkedHashMap<String, UniversalRecord>()

        val conversationOnly = raw.url.substringBefore('?').endsWith("/ConversationTimeline")
        collectTweetResults(rootObj, raw, recordsMap, conversationOnly)

        return recordsMap.values.toList()
    }

    private fun collectTweetResults(
        element: JsonElement?,
        raw: RawData,
        outMap: LinkedHashMap<String, UniversalRecord>,
        conversationOnly: Boolean
    ) {
        if (element == null || element.isJsonNull) return

        if (element.isJsonObject) {
            val obj = element.asJsonObject

            // 在所有信息流中跳过整个广告子树，避免其中的引用或转推内容漏入库。
            if (XAdFilter.isPromoted(obj)) return

            if (conversationOnly) {
                // 详情只读取推文条目和回帖线程，不进入附加推荐、广告等模块。
                val entryId = obj.safeString("entry_id").ifBlank { obj.safeString("entryId") }
                if (entryId.isNotBlank() &&
                    !entryId.startsWith("tweet-") &&
                    !entryId.startsWith("conversationthread-")
                ) return
                val eventInfo = obj.safeJsonObject("client_event_info") ?: obj.safeJsonObject("clientEventInfo")
                val section = eventInfo?.safeJsonObject("details")
                    ?.let { it.safeJsonObject("conversation_details") ?: it.safeJsonObject("conversationDetails") }
                    ?.let { it.safeString("conversation_section").ifBlank { it.safeString("conversationSection") } }
                if (eventInfo?.safeString("component") == "related_tweet" || section == "RelatedTweet") return
            }

            // 遇到 tweet_results 节点直接交由 XTweetParser 解析
            if (obj.has("tweet_results")) {
                val tweetResult = obj.safeJsonObject("tweet_results")?.safeJsonObject("result")
                if (tweetResult != null) {
                    val parsed = XTweetParser.parseTweet(tweetResult, raw)
                    if (parsed != null) {
                        putIfAbsentOrBetter(outMap, parsed.mainRecord)
                        if (!conversationOnly) {
                            for (rel in parsed.relatedRecords) {
                                putIfAbsentOrBetter(outMap, rel)
                            }
                        }
                    }
                }
                // 引用内容仍由单条推文解析器渲染，但不在详情中另建附加推文记录。
                if (conversationOnly) return
            }

            // 继续遍历子字段
            for ((_, value) in obj.entrySet()) {
                collectTweetResults(value, raw, outMap, conversationOnly)
            }
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                collectTweetResults(item, raw, outMap, conversationOnly)
            }
        }
    }

    private fun putIfAbsentOrBetter(map: LinkedHashMap<String, UniversalRecord>, incoming: UniversalRecord) {
        val existing = map[incoming.platformId]
        if (existing == null) {
            map[incoming.platformId] = incoming
        } else {
            // 如果已有记录正文更短（例如旧的是裁剪版），则用新内容替换
            if (incoming.content.length > existing.content.length) {
                map[incoming.platformId] = incoming
            }
        }
    }
}
