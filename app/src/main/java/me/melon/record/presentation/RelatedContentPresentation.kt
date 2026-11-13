package me.melon.record.presentation

import me.melon.record.data.model.RecordIdentity

/** Missing targets are notices, never fabricated captured records. */
internal fun redditMissingParentNotice(parentId: String): String {
    if (parentId.isBlank()) return "父节点关系未记录"
    val label = when (RecordIdentity.of("reddit", parentId).kind) {
        me.melon.record.data.model.RecordIdentityKind.COMMENT -> "父回复未捕获"
        me.melon.record.data.model.RecordIdentityKind.POST -> "父帖子未捕获"
        me.melon.record.data.model.RecordIdentityKind.CONTENT -> "父节点未捕获"
    }
    return "$label：$parentId"
}
