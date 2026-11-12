package me.melon.record.ui.home

import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord

internal sealed interface HomeListItem {
    val key: String
}

internal data class RecordListItem(val record: UniversalRecord) : HomeListItem {
    override val key: String = "record_${record.id}"
}

internal data class AnswerGroupListItem(
    val questionKey: String,
    val questionTitle: String,
    val answers: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "answer_group_${questionKey}_$firstId"
}

internal data class InterestGroupListItem(
    val subjectKey: String,
    val subjectTitle: String,
    val typeLabel: String,
    val interests: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "interest_group_${subjectKey}_$firstId"
}

internal fun groupRecordsForHome(records: List<UniversalRecord>): List<HomeListItem> {
    val result = ArrayList<HomeListItem>(records.size)
    var i = 0
    while (i < records.size) {
        val r = records[i]
        val isZhihuAnswer = r.platform == "zhihu" && r.recordType == "answer"
        if (isZhihuAnswer) {
            val questionKey = zhihuAnswerQuestionKey(r)
            if (questionKey.isNotBlank()) {
                val group = mutableListOf<UniversalRecord>()
                var j = i
                while (j < records.size) {
                    val rr = records[j]
                    if (rr.platform != "zhihu" || rr.recordType != "answer") break
                    if (zhihuAnswerQuestionKey(rr) != questionKey) break
                    group.add(rr)
                    j++
                }

                if (group.size >= 2) {
                    val title = group.first().title.ifBlank { "无标题" }
                    result.add(
                        AnswerGroupListItem(
                            questionKey = questionKey,
                            questionTitle = title,
                            answers = group,
                            firstId = group.first().id
                        )
                    )
                    i = j
                    continue
                }
            }
        }

        val isDoubanInterest = r.platform == "douban" && r.recordType == "interest"
        if (isDoubanInterest) {
            val subjectKey = doubanInterestSubjectKey(r)
            if (subjectKey.isNotBlank()) {
                val group = mutableListOf<UniversalRecord>()
                var j = i
                while (j < records.size) {
                    val rr = records[j]
                    if (rr.platform != "douban" || rr.recordType != "interest") break
                    if (doubanInterestSubjectKey(rr) != subjectKey) break
                    group.add(rr)
                    j++
                }

                if (group.size >= 2) {
                    val (title, typeLabel) = doubanInterestTitleAndType(group.first())
                    result.add(
                        InterestGroupListItem(
                            subjectKey = subjectKey,
                            subjectTitle = title,
                            typeLabel = typeLabel,
                            interests = group,
                            firstId = group.first().id
                        )
                    )
                    i = j
                    continue
                }
            }
        }

        result.add(RecordListItem(r))
        i++
    }
    return result
}

private fun doubanInterestSubjectKey(record: UniversalRecord): String {
    if (record.platform != "douban" || record.recordType != "interest") return ""
    if (record.parentId.isNotBlank() && record.parentId.startsWith("douban_")) {
        return record.parentId
    }
    return try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        val type = if (obj.has("subject_type") && !obj.get("subject_type").isJsonNull) obj.get("subject_type").asString else ""
        val id = if (obj.has("subject_id") && !obj.get("subject_id").isJsonNull) obj.get("subject_id").asString else ""
        if (id.isNotBlank()) {
            if (type.isNotBlank()) "douban_${type}_$id" else "douban_$id"
        } else {
            record.title.ifBlank { "" }
        }
    } catch (_: Exception) {
        record.title.ifBlank { "" }
    }
}

private fun doubanInterestTitleAndType(record: UniversalRecord): Pair<String, String> {
    var subjectTitle = ""
    var subjectType = ""
    try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        if (obj.has("subject_title") && !obj.get("subject_title").isJsonNull) {
            subjectTitle = obj.get("subject_title").asString.trim()
        }
        if (obj.has("subject_type") && !obj.get("subject_type").isJsonNull) {
            subjectType = obj.get("subject_type").asString.trim()
        }
    } catch (_: Exception) {}

    val typeLabel = when (subjectType) {
        "movie" -> "电影短评"
        "book" -> "图书短评"
        "music" -> "音乐短评"
        "game" -> "游戏短评"
        "tv" -> "剧集短评"
        "drama" -> "舞台剧短评"
        else -> {
            if (record.title.contains("：")) {
                record.title.substringBefore("：").trim()
            } else {
                "短评"
            }
        }
    }

    val displayTitle = if (subjectTitle.isNotBlank()) {
        subjectTitle
    } else if (record.title.contains("：")) {
        record.title.substringAfter("：").trim()
    } else {
        record.title.ifBlank { "无标题" }
    }

    return Pair(displayTitle, typeLabel)
}

private fun zhihuAnswerQuestionKey(record: UniversalRecord): String {
    if (record.platform != "zhihu" || record.recordType != "answer") return ""
    if (record.parentId.startsWith("zhihu_question_")) return record.parentId
    return try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        if (obj.has("question_id") && !obj.get("question_id").isJsonNull) "zhihu_question_${obj.get("question_id").asString}" else ""
    } catch (_: Exception) {
        ""
    }
}
