package com.melonhu.indexrecord.data.model

internal enum class RecordIdentityKind { CONTENT, POST, COMMENT }

/** Storage IDs may describe capture roles; identity describes the actual platform content. */
internal data class RecordIdentity(val platform: String, val contentId: String) {
    val kind: RecordIdentityKind get() = when {
        platform == "reddit" && contentId.startsWith("reddit_comment_") -> RecordIdentityKind.COMMENT
        platform == "reddit" && contentId.startsWith("reddit_post_") -> RecordIdentityKind.POST
        platform == "coolapk" && contentId.startsWith("coolapk_comment_") -> RecordIdentityKind.COMMENT
        platform == "hupu" && contentId.startsWith("hupu_comment_") -> RecordIdentityKind.COMMENT
        platform == "hupu" && contentId.startsWith("hupu_post_") -> RecordIdentityKind.POST
        platform == "xiaoheihe" && contentId.startsWith("xiaoheihe_comment_") -> RecordIdentityKind.COMMENT
        platform == "xiaoheihe" && contentId.startsWith("xiaoheihe_post_") -> RecordIdentityKind.POST
        platform == "nga" && contentId.startsWith("nga_comment_") -> RecordIdentityKind.COMMENT
        platform == "nga" && contentId.startsWith("nga_post_") -> RecordIdentityKind.POST
        platform == "bilibili" && BilibiliTopic.fromCommentId(contentId) != null -> RecordIdentityKind.COMMENT
        platform == "bilibili" && (contentId.startsWith("bilibili_video_") || contentId.startsWith("bilibili_opus_")) ->
            RecordIdentityKind.POST
        platform == "netease" && NeteaseThread.fromCommentId(contentId) != null -> RecordIdentityKind.COMMENT
        platform == "netease" && (
            contentId.startsWith("netease_song_") ||
            contentId.startsWith("netease_playlist_") ||
            contentId.startsWith("netease_album_") ||
            contentId.startsWith("netease_program_")
        ) -> RecordIdentityKind.POST
        else -> RecordIdentityKind.CONTENT
    }
    fun storageIds(): Set<String> = when {
        platform == "twitter" && contentId.startsWith("twitter_status_") -> {
            val id = contentId.removePrefix("twitter_status_")
            linkedSetOf("twitter_tweet_$id", "twitter_comment_$id")
        }
        platform == "coolapk" && contentId.startsWith("coolapk_feed_") -> {
            val id = contentId.removePrefix("coolapk_feed_")
            linkedSetOf("coolapk_feed_$id", "coolapk_rating_$id", "coolapk_article_$id")
        }
        else -> setOf(contentId)
    }

    companion object {
        fun of(platform: String, platformId: String): RecordIdentity {
            val contentId = when {
                platform == "twitter" && platformId.startsWith("twitter_tweet_") -> "twitter_status_" + platformId.removePrefix("twitter_tweet_")
                platform == "twitter" && platformId.startsWith("twitter_comment_") -> "twitter_status_" + platformId.removePrefix("twitter_comment_")
                platform == "coolapk" && (
                    platformId.startsWith("coolapk_feed_") ||
                    platformId.startsWith("coolapk_rating_") ||
                    platformId.startsWith("coolapk_article_")
                ) -> "coolapk_feed_" + platformId.substringAfterLast('_')
                else -> platformId
            }
            return RecordIdentity(platform, contentId)
        }
    }
}

internal val RecordContent.identity: RecordIdentity get() = RecordIdentity.of(platform, platformId)

private val captureOrder = compareBy<UniversalRecord> { it.captureTime }
    .thenBy { it.publishTime }.thenBy { it.platformId }

/** Newest content wins. An absent relation in a tweet capture does not retract a captured reply edge. */
internal fun resolveRecordIdentity(records: List<UniversalRecord>): UniversalRecord? {
    val latest = records.maxWithOrNull(captureOrder) ?: return null
    val relation = if (latest.platform == "twitter" && latest.parentId.isBlank()) {
        records.filter { it.parentId.isNotBlank() }.maxWithOrNull(captureOrder)?.parentId.orEmpty()
    } else latest.parentId
    return latest.copy(parentId = relation)
}

internal fun distinctRecordIdentities(records: List<UniversalRecord>): List<UniversalRecord> =
    records.groupBy { it.identity }.values.mapNotNull(::resolveRecordIdentity)

/** Content can be refreshed across roles without changing a list member's storage/navigation role. */
internal fun UniversalRecord.inCaptureRole(role: UniversalRecord): UniversalRecord =
    copy(id = role.id, platformId = role.platformId, recordType = role.recordType)

internal fun refreshCaptureRoles(
    members: List<UniversalRecord>,
    captures: List<UniversalRecord>
): List<UniversalRecord> {
    val byIdentity = (members + captures).groupBy { it.identity }
    return members.map { member ->
        (resolveRecordIdentity(byIdentity[member.identity].orEmpty()) ?: member).inCaptureRole(member)
    }
}
