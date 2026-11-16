package com.melonhu.indexrecord.data.model

/** Relation semantics, not a generic 'parent': each kind has its own navigation/missing policy. */
internal sealed interface RecordRelation {
    val target: RecordIdentity
    data class AnswerToQuestion(override val target: RecordIdentity) : RecordRelation
    data class ReplyTo(override val target: RecordIdentity) : RecordRelation
    data class BelongsToSubject(override val target: RecordIdentity) : RecordRelation
    data class FloorInThread(override val target: RecordIdentity) : RecordRelation
    data class ReferencesContent(override val target: RecordIdentity) : RecordRelation
}

/** Whether the relation target is captured locally, only described by a reference, or absent. */
internal enum class RelatedTargetState {
    RECORDED,
    REFERENCE_ONLY,
    MISSING
}

/** The storage boundary is the only place that interprets the overloaded parentId column. */
internal fun RecordContent.directRelation(): RecordRelation? = relationOf(platform, recordType, parentId)


private fun relationOf(platform: String, recordType: String, parentId: String): RecordRelation? {
    if (parentId.isBlank()) return null
    val target = RecordIdentity.of(platform, parentId)
    return when {
        platform == "zhihu" && recordType == "answer" && parentId.startsWith("zhihu_question_") ->
            RecordRelation.AnswerToQuestion(target)
        platform == "coolapk" && recordType == "answer" && parentId.startsWith("coolapk_question_") ->
            RecordRelation.AnswerToQuestion(target)
        platform == "douban" && recordType != "comment" && parentId.startsWith("douban_") ->
            RecordRelation.BelongsToSubject(target)
        platform == "tieba" && recordType == "floor" -> RecordRelation.FloorInThread(target)
        recordType == "comment" -> RecordRelation.ReplyTo(target)
        platform in setOf("weibo", "threads") -> RecordRelation.ReferencesContent(target)
        else -> null
    }
}

internal fun RecordContent.replyTarget(): RecordIdentity? =
    (directRelation() as? RecordRelation.ReplyTo)?.target
