package me.melon.record.ui.components

// Compatibility facade for existing callers; presentation owns metadata and preview semantics.
internal typealias RedditMetadata = me.melon.record.presentation.RedditMetadata

internal fun redditPreviewText(markdown: String): String =
    me.melon.record.presentation.redditPreviewText(markdown)
