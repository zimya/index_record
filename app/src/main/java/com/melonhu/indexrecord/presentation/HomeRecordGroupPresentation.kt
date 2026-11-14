package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.directRelation

internal enum class HomeRecordGroupKind { ANSWERS, INTERESTS }

internal data class HomeRecordGroupPresentation(
    val target: RecordIdentity,
    val kind: HomeRecordGroupKind,
    val title: String,
    val typeLabel: String = ""
) {
    // Display text is not identity: renamed targets still group, same-named targets do not.
    fun sameGroupAs(other: HomeRecordGroupPresentation?): Boolean =
        other != null && target == other.target && kind == other.kind
}

/** Pure grouping policy. Missing identity evidence never falls back to a display title. */
internal fun homeRecordGroupPresentation(record: UniversalRecord): HomeRecordGroupPresentation? {
    val kind = when {
        record.platform == "zhihu" && record.recordType == "answer" -> HomeRecordGroupKind.ANSWERS
        record.platform == "douban" && record.recordType == "interest" -> HomeRecordGroupKind.INTERESTS
        else -> return null
    }
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    return when (kind) {
        HomeRecordGroupKind.ANSWERS -> {
            val target = (record.directRelation() as? RecordRelation.AnswerToQuestion)?.target
                ?: metadata.zhihu?.questionTarget ?: return null
            HomeRecordGroupPresentation(target, kind, record.title.ifBlank { "无标题" })
        }
        HomeRecordGroupKind.INTERESTS -> {
            val subject = metadata.douban ?: return null
            val target = (record.directRelation() as? RecordRelation.BelongsToSubject)?.target
                ?: subject.subjectTarget ?: return null
            val typeLabel = when (subject.subjectType) {
                "movie" -> "电影短评"
                "book" -> "图书短评"
                "music" -> "音乐短评"
                "game" -> "游戏短评"
                "tv" -> "剧集短评"
                "drama" -> "舞台剧短评"
                else -> record.title.takeIf { it.contains("：") }?.substringBefore("：")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "短评"
            }
            val title = subject.subjectTitle.trim().ifBlank {
                record.title.substringAfter("：").trim().ifBlank { "无标题" }
            }
            HomeRecordGroupPresentation(target, kind, title, typeLabel)
        }
    }
}
