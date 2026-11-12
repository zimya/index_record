package me.melon.record.ui.components

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.markdown.MarkdownContentFormat
import me.melon.record.markdown.MdBlockQuote
import me.melon.record.markdown.MdDocument
import me.melon.record.markdown.MdFencedCodeBlock
import me.melon.record.markdown.MdFootnoteReference
import me.melon.record.markdown.MdGif
import me.melon.record.markdown.MdHardLineBreak
import me.melon.record.markdown.MdHeading
import me.melon.record.markdown.MdImage
import me.melon.record.markdown.MdInlineCode
import me.melon.record.markdown.MdListItem
import me.melon.record.markdown.MdNode
import me.melon.record.markdown.MdParagraph
import me.melon.record.markdown.MdTableCell
import me.melon.record.markdown.MdText
import me.melon.record.markdown.contentToMdAst

/** Read-only presentation of the canonical Reddit metadata; never infer absent engagement counts. */
internal class RedditMetadata(extraMetadata: String) {
    private val values = runCatching {
        JsonParser.parseString(extraMetadata).asJsonObject
    }.getOrElse { JsonObject() }

    val isScoreHidden: Boolean? = flag("is_score_hidden")
    val score: Long? = values.signedScore().takeUnless { isScoreHidden == true }
    val commentCount: Long? = values.optionalEngagementCount("comment_count")
    val shareCount: Long? = values.optionalEngagementCount("share_count")
    val crosspostCount: Long? = values.optionalEngagementCount("crosspost_count")
    val childCount: Long? = values.optionalEngagementCount("child_count")
    val commentsHasNextPage: Boolean? = flag("comments_has_next_page")
    val postId: String = text("post_id")
    val parentFullname: String = text("parent_fullname")
    val subreddit: String = text("subreddit")
    val subredditId: String = text("subreddit_id")
    val flair: String = text("flair")
    val isNsfw: Boolean? = flag("is_nsfw")
    val isSpoiler: Boolean? = flag("is_spoiler")
    // Canonical parser timestamps are milliseconds since Unix epoch.
    val editedAt: String = text("edited_at")

    val additionalFields: List<Pair<String, String>> = values.entrySet().mapNotNull { (key, value) ->
        if (key in KNOWN_KEYS || !value.isJsonPrimitive) return@mapNotNull null
        value.asString.takeIf { it.isNotBlank() }?.let { key to it }
    }

    private fun text(key: String): String =
        values.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun flag(key: String): Boolean? =
        values.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toBooleanStrictOrNull()

    private companion object {
        val KNOWN_KEYS = setOf(
            "score", "is_score_hidden", "comment_count", "share_count", "crosspost_count", "child_count",
            "comments_has_next_page", "post_id", "parent_fullname",
            "subreddit", "subreddit_id", "flair", "is_nsfw", "is_spoiler", "edited_at",
            // Parser bookkeeping is not platform/user metadata and must not appear as badges.
            "content_format", "body_source", "body_complete", "body_length", "media_complete",
            "comment_forest_count"
        )
    }
}

/** Only the preview is flattened; the captured Markdown is never rewritten. */
internal fun redditPreviewText(markdown: String): String {
    val document = contentToMdAst(markdown, MarkdownContentFormat.MARKDOWN, enableMath = false)
    val text = StringBuilder()
    fun append(node: MdNode) {
        when (node) {
            is MdText -> text.append(node.text)
            is MdInlineCode -> text.append(node.literal)
            is MdFencedCodeBlock -> text.append(node.code).append(' ')
            is MdImage -> text.append("[图片]")
            is MdGif -> text.append("[GIF]")
            is MdHardLineBreak -> text.append(' ')
            is MdFootnoteReference -> text.append('[').append(node.label).append(']')
            else -> {
                node.children.forEach { child ->
                    append(child)
                    // Images/GIFs can be standalone block children without a paragraph wrapper.
                    if (node is MdDocument || node is MdBlockQuote || node is MdListItem) text.append(' ')
                }
                if (node is MdParagraph || node is MdHeading || node is MdListItem ||
                    node is MdTableCell || node is MdBlockQuote || node is MdDocument
                ) text.append(' ')
            }
        }
    }
    append(document)
    return text.toString().replace(Regex("\\s+"), " ").trim()
}
