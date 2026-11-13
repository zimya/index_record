package me.melon.record.presentation

import me.melon.record.data.model.RecordRelation
import me.melon.record.data.model.RelatedTargetState

/** Labels describe the source's explicit edge, never the target's platform or record type. */
internal fun relatedRecordLabel(relation: RecordRelation): String = when (relation) {
    is RecordRelation.AnswerToQuestion -> "所属问题"
    is RecordRelation.ReplyTo -> "回复对象"
    is RecordRelation.BelongsToSubject -> "所属条目"
    is RecordRelation.FloorInThread -> "所属主帖"
    is RecordRelation.ReferencesContent -> "引用内容"
}

internal fun relatedTargetStateLabel(state: RelatedTargetState): String = when (state) {
    RelatedTargetState.RECORDED -> "已记录"
    RelatedTargetState.REFERENCE_ONLY -> "仅引用"
    RelatedTargetState.MISSING -> "本地缺失"
}

internal fun relatedTargetNotice(state: RelatedTargetState): String? = when (state) {
    RelatedTargetState.RECORDED -> null
    RelatedTargetState.REFERENCE_ONLY -> "仅有引用信息，尚未记录目标正文"
    RelatedTargetState.MISSING -> "本地尚未记录关联内容"
}

/** Missing branches use the same wording regardless of platform or target ID prefix. */
internal fun missingRelatedContentNotice(targetId: String): String =
    relatedTargetNotice(RelatedTargetState.MISSING) + targetId.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()

internal fun redditMissingParentNotice(parentId: String): String = missingRelatedContentNotice(parentId)
