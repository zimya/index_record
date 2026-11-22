package com.melonhu.indexrecord.parser.reddit

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.htmlToMdAst
import com.melonhu.indexrecord.markdown.toMarkdown
import com.melonhu.indexrecord.parser.RawDataParser
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.ParseDiagnostic
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.time.OffsetDateTime

/** gql-fed returns many unrelated operations at the same URL; only the shared projection is parsed. */
object RedditParser : RawDataParser {
    const val HOST_APP = "com.reddit.frontpage"
    override val platform = "reddit"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val uri = URI(cleanUrl)
        uri.scheme.equals("https", true) && uri.host.equals("gql-fed.reddit.com", true) &&
            (raw.hostApp.isBlank() || raw.hostApp == HOST_APP)
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    /** Used for legacy unfiltered raw captures, including sparse-ad-before-full-ad ordering. */
    fun advertisingPostIds(raw: RawData): Set<String> = runCatching {
        if (!canHandle(raw.url, raw)) return emptySet()
        val value = JSONTokener(raw.json).nextValue()
        val response = (value as? JSONObject)?.takeIf { it.has("response") }?.opt("response") ?: value
        linkedSetOf<String>().also { RedditCaptureFilter.collectAdvertisingIds(response, it) }
    }.getOrDefault(emptySet())

    fun readPostIds(raw: RawData): Set<String> = readVisits(raw).mapTo(linkedSetOf()) { it.postId }

    /** Unknown/legacy operations retain forest evidence; known auxiliary operations do not. */
    fun readVisits(raw: RawData): List<RedditReadVisit> = runCatching {
        if (!canHandle(raw.url, raw)) return emptyList()
        val input = JSONTokener(raw.json).nextValue()
        val wrapped = (input as? JSONObject)?.takeIf { it.has("response") }
        val projected = RedditCaptureFilter.envelope(wrapped?.opt("response") ?: input, wrapped?.opt("request"), linkedSetOf())
            ?: return emptyList()
        val response = projected.opt("response")
        val request = projected.opt("request")
        val visits = linkedSetOf<RedditReadVisit>()
        fun collect(value: JSONObject?, query: JSONObject?) {
            if (query?.string("operationName") in setOf("HomeFeedSdui", "HomeFeedPostsByIds", "PostsByIds", "AdaptivePDP")) return
            val variables = query?.optJSONObject("variables")
            val targets = sequenceOf("postId", "id").mapNotNull { variables?.nullableString(it) }
                .filter { it.startsWith("t3_") }.map(::postId).filter { it.isNotEmpty() }.toSet()
            val token = variables?.nullableString("pdpConsistencyToken")?.takeIf { it.isNotBlank() }
            val data = value?.optJSONObject("data") ?: return
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                fun collectItem(item: JSONObject) {
                    val type = item.string("__typename")
                    val id = when {
                        (key in setOf("postInfoById", "postsInfoByIds") || type in setOf("SubredditPost", "ProfilePost")) &&
                            item.optJSONObject("commentForest") != null -> item.string("id")
                        type == "CommentForest" -> item.string("postId").ifEmpty { requestPostId(query) }
                        else -> ""
                    }
                    val normalizedId = postId(id)
                    if (normalizedId.isNotEmpty() && targets.all { it == normalizedId }) {
                        visits.add(RedditReadVisit(normalizedId, token, raw.timestamp))
                    }
                }
                when (val item = data.opt(key)) {
                    is JSONObject -> collectItem(item)
                    is JSONArray -> item.objects().forEach(::collectItem)
                }
            }
        }
        when (response) {
            is JSONObject -> collect(response, request as? JSONObject)
            is JSONArray -> for (i in 0 until response.length()) {
                collect(response.optJSONObject(i), (request as? JSONArray)?.optJSONObject(i))
            }
        }
        visits.toList()
    }.getOrDefault(emptyList())

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult =
        parseResultWithAdvertisingIds(raw, cleanUrl, emptySet())

    fun parseResultWithAdvertisingIds(raw: RawData, cleanUrl: String, excluded: Set<String>): ParseResult =
        ParseResult.attempt("reddit.decode") {
            val diagnostics = mutableListOf<ParseDiagnostic>()
            val records = parseWithAdvertisingIds(raw, cleanUrl, excluded, diagnostics = diagnostics)
            ParseResult.decoded(records, diagnostics)
        }

    fun parseWithAdvertisingIds(
        raw: RawData,
        cleanUrl: String,
        excluded: Set<String>,
        readPostIds: Set<String>? = null,
        diagnostics: MutableList<ParseDiagnostic> = mutableListOf()
    ): List<UniversalRecord> {
        if (!canHandle(cleanUrl, raw)) return emptyList()
        val tokener = JSONTokener(raw.json)
        val input = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "Trailing data after Reddit response" }
        require(input is JSONObject || input is JSONArray) { "Expected Reddit object or batch" }
        val wrapped = (input as? JSONObject)?.takeIf { it.has("response") }
        val response = wrapped?.opt("response") ?: input
        val request = wrapped?.opt("request")
        require(response is JSONObject || response is JSONArray) { "Expected Reddit response object or batch" }
        fun reportErrors(value: JSONObject, location: String) {
            val errors = value.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                diagnostics.add(ParseDiagnostic(location, "Reddit GraphQL returned ${errors.length()} errors"))
            }
        }
        when (response) {
            is JSONObject -> reportErrors(response, "reddit.response.errors")
            is JSONArray -> for (index in 0 until response.length()) {
                response.optJSONObject(index)?.let { reportErrors(it, "reddit.response[$index].errors") }
            }
        }
        val ads = excluded.toMutableSet()
        val filtered = RedditCaptureFilter.envelope(response, request, ads) ?: return emptyList()
        val records = linkedMapOf<String, UniversalRecord>()

        fun emit(record: UniversalRecord) {
            records[record.platformId]?.let { RedditRelations.mergeRecord(it, record) }
            records[record.platformId] = record
        }

        fun parseResponse(value: JSONObject, query: JSONObject?) {
            val data = value.optJSONObject("data") ?: return
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                fun parseValue(item: JSONObject) {
                    val type = item.string("__typename")
                    when {
                        key == "homeV3" -> parseFeed(item, raw, ::emit)
                        key == "postInfoById" || key == "postsInfoByIds" || type in setOf("SubredditPost", "ProfilePost") -> {
                            val root = parsePost(item, raw) ?: return
                            emit(root)
                            item.optJSONObject("commentForest")?.let { forest ->
                                parseForest(forest, item.string("id"), item.optJSONObject("subreddit"), raw, ::emit)
                            }
                        }
                        key == "commentInfoById" || key == "commentsInfoByIds" || type == "Comment" -> {
                            val root = item.string("postId").ifEmpty { requestPostId(query) }
                            if (RedditCaptureFilter.fullname(root, "t3_") !in ads) parseComment(item, null, root, null, raw)?.let(::emit)
                        }
                        type == "CommentForest" -> {
                            val root = item.string("postId").ifEmpty { requestPostId(query) }
                            if (RedditCaptureFilter.fullname(root, "t3_") !in ads) parseForest(item, root, null, raw, ::emit)
                        }
                    }
                }
                when (val valueAtRoot = data.opt(key)) {
                    is JSONObject -> try { parseValue(valueAtRoot) } catch (e: Exception) {
                                            diagnostics.add(ParseDiagnostic("reddit.data.$key", e.message.orEmpty(), e))
                                        }
                    is JSONArray -> valueAtRoot.objects().forEachIndexed { index, item ->
                                            try { parseValue(item) } catch (e: Exception) {
                                                diagnostics.add(ParseDiagnostic("reddit.data.$key[$index]", e.message.orEmpty(), e))
                                            }
                                        }
                }
            }
        }

        when (val projected = filtered.opt("response")) {
            is JSONObject -> parseResponse(projected, filtered.optJSONObject("request"))
            is JSONArray -> {
                val requests = filtered.optJSONArray("request")
                for (i in 0 until projected.length()) projected.optJSONObject(i)?.let {
                    parseResponse(it, requests?.optJSONObject(i))
                }
            }
        }
        return records.values.filter { record ->
            readPostIds == null || if (record.recordType == "post") {
                record.platformId in readPostIds
            } else {
                JSONObject(record.extraMetadata).optString("post_id", "") in readPostIds
            }
        }
    }

    private fun parsePost(post: JSONObject, raw: RawData): UniversalRecord? {
        val id = postId(post.string("id"))
        if (id.isEmpty()) return null
        val meta = commonMetadata(post)
        subredditMetadata(post.optJSONObject("subreddit"), meta)
        post.optJSONObject("flair")?.string("text")?.takeIf { it.isNotBlank() }?.let { meta.put("flair", it) }
        count(post, "commentCount", meta, "comment_count")
        count(post, "crosspostCount", meta, "crosspost_count")
        val stats = post.optJSONObject("postStats")
        count(stats, "shareAllTotal", meta, "share_count")
        count(stats, "viewCountTotal", meta, "view_count")
        post.optJSONObject("crosspostRoot")?.string("id")?.let(::postId)?.takeIf { it.isNotEmpty() }
            ?.let { meta.put("crosspost_root_id", it) }
        post.optJSONObject("poll")?.let { meta.put("poll", it) }
        post.optJSONObject("commentForest")?.let { forestMetadata(it, meta) }
        val body = body(post, meta, allowMedia = true)
        return record(post, raw, id, "post", meta).apply {
            title = post.string("postTitle").ifEmpty { post.string("title") }
            content = body
            sourceUrl = permalink(post.string("permalink")).ifEmpty { "https://www.reddit.com/comments/${id.removePrefix("reddit_post_")}/" }
        }
    }

    private fun parseForest(forest: JSONObject, postFullname: String, subreddit: JSONObject?, raw: RawData, emit: (UniversalRecord) -> Unit) {
        val root = postId(postFullname)
        forest.optJSONArray("trees")?.objects()?.forEach { wrapper ->
            // Null nodes carrying `more` are pagination placeholders, not captured comments.
            val node = wrapper.optJSONObject("node") ?: return@forEach
            parseComment(node, wrapper, root, subreddit, raw)?.let(emit)
        }
    }

    private fun parseComment(comment: JSONObject, wrapper: JSONObject?, postContext: String, subreddit: JSONObject?, raw: RawData): UniversalRecord? {
        val id = commentId(comment.string("id"))
        if (id.isEmpty()) return null
        val root = postId(comment.string("postId")).ifEmpty { postId(postContext) }.ifEmpty {
            postId(Regex("/comments/([a-z0-9]+)/", RegexOption.IGNORE_CASE).find(comment.string("permalink"))?.groupValues?.get(1).orEmpty())
        }
        val depth = wrapper?.long("depth")?.takeIf { it >= 0 }
        val direct = relationId(wrapper?.string("parentId").orEmpty()).ifEmpty { relationId(comment.string("parentId")) }
            .ifEmpty { if (depth == 0L) root else "" }.takeUnless { it == id }.orEmpty()
        val meta = commonMetadata(comment)
        if (root.isNotEmpty()) meta.put("post_id", root)
        if (direct.isNotEmpty()) meta.put("parent_fullname", nativeId(direct))
        if (depth != null) meta.put("depth", depth)
        // GraphQL childCount can include descendants. It is not a proven direct reply count.
        count(wrapper, "childCount", meta, "child_count")
        count(comment, "commentCount", meta, "comment_count")
        count(comment, "replyCount", meta, "reply_count")
        subredditMetadata(subreddit, meta)
        val body = body(comment, meta, allowMedia = false)
        return record(comment, raw, id, "comment", meta).apply {
            parentId = direct
            content = body
            sourceUrl = permalink(comment.string("permalink")).ifEmpty {
                if (root.isNotEmpty()) "https://www.reddit.com/comments/${root.removePrefix("reddit_post_")}/_/${id.removePrefix("reddit_comment_")}/" else ""
            }
        }
    }

    private fun record(value: JSONObject, raw: RawData, id: String, type: String, meta: JSONObject): UniversalRecord {
        val author = value.optJSONObject("authorInfo")
        val handle = author?.optJSONObject("attributes")?.optJSONObject("redditHandleInfo")
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = platform, recordType = type, sourceApp = HOST_APP, platformId = id,
            authorId = author?.string("id").orEmpty(),
            authorName = handle?.string("username").orEmpty().ifEmpty { author?.string("name").orEmpty() }
                .ifEmpty { handle?.string("displayName").orEmpty() },
            authorAvatar = safeUrl(author?.optJSONObject("iconSmall")?.string("url").orEmpty())
                .ifEmpty { safeUrl(author?.optJSONObject("snoovatarIcon")?.string("url").orEmpty()) },
            publishTime = parseDate(value.string("createdAt")), captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun commonMetadata(value: JSONObject): JSONObject = JSONObject().apply {
        put("content_format", "markdown")
        value.string("id").takeIf { it.isNotEmpty() }?.let { put("fullname", it) }
        val hidden = value.boolean("isScoreHidden")
        if (hidden != null) put("is_score_hidden", hidden)
        // A hidden score is a server placeholder, not an observed signed net vote total.
        if (hidden != true) value.long("score")?.let { put("score", it) }
        mapOf(
            "isNsfw" to "is_nsfw", "isSpoiler" to "is_spoiler", "isArchived" to "is_archived",
            "isLocked" to "is_locked", "isStickied" to "is_stickied", "isSelfPost" to "is_self_post",
            "isRemoved" to "is_removed", "isAdminTakedown" to "is_admin_takedown", "isOP" to "is_op"
        ).forEach { (source, target) -> value.boolean(source)?.let { put(target, it) } }
        mapOf("distinguishedAs" to "distinguished_as", "removedByCategory" to "removed_by_category").forEach { (source, target) ->
            value.string(source).takeIf { it.isNotBlank() }?.let { put(target, it) }
        }
        value.optJSONObject("authorFlair")?.string("text")?.takeIf { it.isNotBlank() }?.let { put("author_flair", it) }
        value.optJSONArray("awardings")?.takeIf { it.length() > 0 }?.let { put("awardings", it) }
        value.optJSONObject("tombstone")?.let { put("tombstone", it) }
        value.optJSONObject("content")?.optJSONArray("richtextMedia")?.takeIf { it.length() > 0 }?.let { put("richtext_media", it) }
        parseDate(value.string("editedAt")).takeIf { it > 0 }?.let { put("edited_at", it) }
    }

    private fun subredditMetadata(subreddit: JSONObject?, meta: JSONObject) {
        subreddit?.string("name")?.ifEmpty { subreddit.string("prefixedName").removePrefix("r/") }
            ?.takeIf { it.isNotBlank() }?.let { meta.put("subreddit", it.removePrefix("r/")) }
        subreddit?.string("id")?.takeIf { it.isNotBlank() }?.let { meta.put("subreddit_id", it) }
    }

    private fun forestMetadata(forest: JSONObject, meta: JSONObject) {
        forest.optJSONObject("pageInfo")?.let { page ->
            page.boolean("hasNextPage")?.let { meta.put("comments_has_next_page", it) }
            // pageInfo.commentCount (318 in the sample) is not post.commentCount (409).
            count(page, "commentCount", meta, "comment_forest_count")
        }
        forest.string("appliedSort").takeIf { it.isNotEmpty() }?.let { meta.put("comment_sort", it) }
        val more = JSONArray()
        forest.optJSONArray("trees")?.objects()?.forEach { tree ->
            tree.optJSONObject("more")?.let { value ->
                more.put(JSONObject().apply {
                    tree.string("parentId").takeIf { it.isNotEmpty() }?.let { put("parent_fullname", it) }
                    tree.long("depth")?.let { put("depth", it) }
                    value.long("count")?.let { put("count", it) }
                    value.string("cursor").takeIf { it.isNotEmpty() }?.let { put("cursor", it) }
                })
            }
        }
        if (more.length() > 0) meta.put("comment_more", more)
    }

    private val RICHTEXT_MEDIA_REGEX = Regex("""!\[([^\]]*)\]\(([^)\s]+)(\s+(?:"[^"]*"|'[^']*'))?\)""")

    private fun body(value: JSONObject, meta: JSONObject, allowMedia: Boolean): String {
        val content = value.optJSONObject("content")
        val markdown = content?.nullableString("markdown")
        val html = content?.nullableString("html")
        val preview = content?.nullableString("preview")
        val text = when {
            markdown != null -> resolveRichtextMedia(markdown, content) // No trimming, HTML conversion, or Markdown escaping of the real source.
            html != null -> runCatching { htmlToMdAst(html).toMarkdown() }.getOrElse { escapeText(html) }
            preview != null -> escapeText(preview)
            else -> ""
        }
        if (markdown != null || html != null || preview != null) {
            meta.put("body_source", if (markdown != null) "markdown" else if (html != null) "html" else "preview")
            meta.put("body_complete", markdown != null || html != null)
            meta.put("body_length", text.length)
        }
        val blocks = linkedSetOf<String>()
        if (allowMedia) {
            value.optJSONObject("gallery")?.optJSONArray("items")?.objects()?.forEach { item ->
                mediaBlock(item.optJSONObject("media"), item.string("caption"))?.let(blocks::add)
            }
            if (blocks.isEmpty()) mediaBlock(value.optJSONObject("media"))?.let(blocks::add)
            val outbound = safeUrl(value.optJSONObject("outboundLink")?.string("url").orEmpty()).ifEmpty { safeUrl(value.string("url")) }
            if (blocks.isEmpty() && outbound.isNotEmpty() && !isRedditPermalink(outbound) && !text.contains(outbound)) {
                val path = runCatching { URI(outbound).path.orEmpty().lowercase() }.getOrDefault("")
                blocks.add(if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".webp")) {
                    "![图片](${markdownUrl(outbound)})"
                } else "[查看链接](${markdownUrl(outbound)})")
            }
        }
        if (blocks.isNotEmpty()) {
            meta.put("media_markdown", JSONArray(blocks.toList()))
            meta.put("media_complete", true)
        }
        return RedditRelations.combineBody(text, blocks.toList())
    }

    private fun parseFeed(feed: JSONObject, raw: RawData, emit: (UniversalRecord) -> Unit) {
        feed.optJSONObject("elements")?.optJSONArray("edges")?.objects()?.forEach { edge ->
            val node = edge.optJSONObject("node") ?: return@forEach
            val id = postId(node.string("groupId"))
            if (id.isEmpty()) return@forEach
            var title = ""
            var text = ""
            var author = ""
            var created = 0L
            val meta = JSONObject().put("content_format", "markdown").put("fullname", nativeId(id))
            val media = linkedSetOf<String>()
            node.optJSONArray("cells")?.objects()?.forEach { cell ->
                when (cell.string("__typename")) {
                    "TitleCell" -> title = cell.string("title")
                    "PreviewTextCell" -> {
                        text = escapeText(cell.string("text"))
                        meta.put("body_source", "preview").put("body_complete", false).put("body_length", text.length)
                    }
                    "MetadataCell" -> {
                        author = cell.string("authorName").removePrefix("u/")
                        created = parseDate(cell.string("createdAt"))
                        cell.string("detailsString").takeIf { it.startsWith("r/") }?.let { meta.put("subreddit", it.removePrefix("r/")) }
                        cell.string("joinSubredditId").takeIf { it.isNotEmpty() }?.let { meta.put("subreddit_id", it) }
                    }
                    "ActionCell" -> {
                        cell.boolean("isScoreHidden")?.let { meta.put("is_score_hidden", it) }
                        if (cell.boolean("isScoreHidden") != true) cell.long("score")?.let { meta.put("score", it) }
                        count(cell, "commentCount", meta, "comment_count")
                        count(cell, "shareCount", meta, "share_count")
                        count(cell, "crosspostCount", meta, "crosspost_count")
                    }
                    "ImageCell", "VideoCell" -> mediaBlock(cell.optJSONObject("media"))?.let(media::add)
                    "GalleryCell" -> cell.optJSONArray("pages")?.objects()?.forEach { page -> mediaBlock(page.optJSONObject("image"))?.let(media::add) }
                    "FlairCell" -> cell.string("text").takeIf { it.isNotBlank() }?.let { meta.put("flair", it) }
                }
            }
            if (media.isNotEmpty()) meta.put("media_markdown", JSONArray(media.toList())).put("media_complete", false)
            emit(UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = platform, recordType = "post", sourceApp = HOST_APP, platformId = id, title = title,
                content = RedditRelations.combineBody(text, media.toList()), authorName = author,
                sourceUrl = "https://www.reddit.com/comments/${id.removePrefix("reddit_post_")}/",
                publishTime = created, captureTime = raw.timestamp, extraMetadata = meta.toString()
            ))
        }
    }

    private fun resolveRichtextMedia(markdown: String, content: JSONObject): String {
        val items = content.optJSONArray("richtextMedia")?.objects().orEmpty()
        if (items.isEmpty()) return markdown
        val mediaById = items.mapNotNull { item ->
            item.string("id").takeIf { it.isNotEmpty() }?.let { it to item }
        }.toMap()
        if (mediaById.isEmpty()) return markdown
        return RICHTEXT_MEDIA_REGEX.replace(markdown) { match ->
            val asset = mediaById[match.groupValues[2]] ?: return@replace match.value
            if (RedditCaptureFilter.isAd(asset)) return@replace match.value
            val (image, videoUrl) = extractMediaUrls(asset)
            when {
                videoUrl.isNotEmpty() -> videoMarkdown(image, videoUrl)
                image.isNotEmpty() -> "![${match.groupValues[1]}](${markdownUrl(image)}${match.groupValues[3]})"
                else -> match.value
            }
        }
    }

    private fun extractMediaUrls(media: JSONObject): Pair<String, String> {
        val still = media.optJSONObject("still")
        val image = bestImage(still ?: media)
        val video = media.optJSONObject("video") ?: media.optJSONObject("streaming")
        val videoUrl = sequenceOf("fallbackUrl", "mp4Url", "url", "hlsUrl", "dashUrl")
            .map { safeUrl(video?.string(it).orEmpty()) }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        return image to videoUrl
    }

    private fun videoMarkdown(image: String, videoUrl: String): String =
        if (image.isEmpty()) "[▶ 点击播放视频](${markdownUrl(videoUrl)})" else
            "[![视频封面](${markdownUrl(image)})](${markdownUrl(videoUrl)})\n\n[▶ 点击播放视频](${markdownUrl(videoUrl)})"

    private fun mediaBlock(media: JSONObject?, caption: String = ""): String? {
        if (media == null || RedditCaptureFilter.isAd(media)) return null
        val (image, videoUrl) = extractMediaUrls(media)
        val alt = caption.ifEmpty { media.optJSONObject("still")?.string("altText").orEmpty() }.ifEmpty { "图片" }
        return when {
            videoUrl.isNotEmpty() -> videoMarkdown(image, videoUrl)
            image.isNotEmpty() -> "![${escapeLabel(alt)}](${markdownUrl(image)})"
            else -> null
        }
    }

    private fun bestImage(media: JSONObject): String {
        safeUrl(media.string("url")).takeIf { it.isNotEmpty() }?.let { return it }
        safeUrl(media.string("path")).takeIf { it.isNotEmpty() }?.let { return it }
        media.optJSONObject("source")?.let { safeUrl(it.string("url")) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return sequenceOf("xxxlarge", "xxlarge", "xlargePlus720", "xlarge", "midlarge480", "large", "medium", "small")
            .mapNotNull { media.optJSONObject(it) }.map { safeUrl(it.string("url")) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun count(source: JSONObject?, key: String, meta: JSONObject, target: String) {
        source?.long(key)?.takeIf { it >= 0 }?.let { meta.put(target, it) }
    }

    internal fun postId(value: String): String = RedditCaptureFilter.fullname(value, "t3_")
        .takeIf { it.isNotEmpty() }?.replaceFirst("t3_", "reddit_post_").orEmpty()
    internal fun commentId(value: String): String = RedditCaptureFilter.fullname(value, "t1_")
        .takeIf { it.isNotEmpty() }?.replaceFirst("t1_", "reddit_comment_").orEmpty()
    private fun relationId(value: String): String = when {
        value.startsWith("t3_") || value.startsWith("reddit_post_") -> postId(value)
        value.startsWith("t1_") || value.startsWith("reddit_comment_") -> commentId(value)
        else -> ""
    }
    private fun nativeId(value: String): String = value.replaceFirst("reddit_post_", "t3_").replaceFirst("reddit_comment_", "t1_")
    private fun requestPostId(request: JSONObject?): String {
        val variables = request?.optJSONObject("variables") ?: return ""
        return sequenceOf("postId", "id").map { variables.string(it) }.firstOrNull { it.startsWith("t3_") }.orEmpty()
    }
    internal fun parseDate(value: String): Long = runCatching {
        val normalized = value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    }.getOrDefault(0L)
    private fun permalink(value: String): String = when {
        value.startsWith("/") && !value.startsWith("//") -> "https://www.reddit.com$value"
        isRedditPermalink(value) -> value
        else -> ""
    }
    private fun isRedditPermalink(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && (uri.host == "reddit.com" || uri.host?.endsWith(".reddit.com") == true)
    }.getOrDefault(false)
    private fun safeUrl(value: String): String = runCatching {
        val uri = URI(value)
        value.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() }.orEmpty()
    }.getOrDefault("")
    private fun markdownUrl(value: String): String = value.replace("(", "%28").replace(")", "%29").replace(" ", "%20")
    private fun escapeLabel(value: String): String = value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")
    private fun escapeText(value: String): String = buildString {
        value.forEach { c -> if (c in "\\`*_{}[]<>#!|~") append('\\'); append(c) }
    }
    private fun JSONObject.nullableString(key: String): String? = opt(key) as? String
    private fun JSONObject.string(key: String): String = nullableString(key).orEmpty()
    private fun JSONObject.long(key: String): Long? = opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
    private fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
}
