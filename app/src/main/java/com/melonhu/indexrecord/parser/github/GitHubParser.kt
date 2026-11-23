package com.melonhu.indexrecord.parser.github

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * GitHub issue / pull request / discussion / release / README 与评论存档。
 * REST 与 GraphQL 返回的正文均为 Markdown。
 */
object GitHubParser : RawDataParser {
    override val platform = "github"
    const val HOST_APP = "com.github.android"

    private val REST_PATH = Regex("""/repos/([^/]+)/([^/]+)/(issues|pulls|discussions|releases)/(\d+)""")
    private val COMMENT_PATH = Regex("""/repos/([^/]+)/([^/]+)/issues/(\d+)/comments""")

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val uri = URI(cleanUrl)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val trustedHost = host == "api.github.com" || host == "github.com" || host.endsWith(".github.com")
        trustedHost && (
            path.contains("/graphql") || REST_PATH.containsMatchIn(path) ||
                COMMENT_PATH.containsMatchIn(path) || path.endsWith("/readme")
            )
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported GitHub endpoint")
        val root = JsonParser.parseString(raw.json)
        val records = linkedMapOf<String, UniversalRecord>()

        fun keep(record: UniversalRecord?) {
            if (record != null && record.platformId.isNotEmpty()) records[record.platformId] = record
        }

        when {
            root.isJsonArray -> root.asJsonArray.forEach {
                if (it.isJsonObject) keep(commentRecord(it.asJsonObject, raw, cleanUrl))
            }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                if (obj.has("title") || obj.has("pull_request") || obj.has("body")) {
                    keep(contentRecord(obj, raw, cleanUrl))
                }
                obj.obj("data")?.obj("repository")?.let { repo ->
                    mapOf("issue" to "issue", "pullRequest" to "pr", "discussion" to "discussion")
                        .forEach { (key, type) -> repo.obj(key)?.let { keep(graphqlRecord(it, raw, type)) } }
                }
                obj.obj("readme")?.let { keep(readmeRecord(it, raw, cleanUrl)) }
            }
        }

        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun contentRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val type = when {
            node.has("pull_request") -> "pr"
            node.has("discussion") -> "discussion"
            node.str("tag_name").isNotEmpty() -> "release"
            else -> "issue"
        }
        val number = node["number"]?.asLong ?: 0L
        val fullName = node.obj("base")?.obj("repo")?.str("full_name").orEmpty()
        val owner = fullName.substringBefore('/', ownRepo(cleanUrl)?.first.orEmpty())
        val repo = fullName.substringAfter('/', ownRepo(cleanUrl)?.second.orEmpty())
        if (owner.isEmpty() || repo.isEmpty()) return null
        val suffix = if (number > 0L) number.toString() else node.str("tag_name").ifBlank { node.str("id") }
        if (suffix.isEmpty()) return null

        val author = node.obj("user")
        val labels = node.array("labels")
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.str("name") }
            .filter { it.isNotBlank() }
        val meta = JsonObject().apply {
            addProperty("repo", "$owner/$repo")
            addProperty("number", number)
            addProperty("state", node.str("state"))
            node["comments"]?.takeIf { it.isJsonPrimitive }?.let { add("comments", it) }
            node["merged"]?.takeIf { it.isJsonPrimitive }?.let { add("merged", it) }
            node.str("tag_name").takeIf { it.isNotBlank() }?.let { addProperty("tag", it) }
            if (labels.isNotEmpty()) add("labels", JsonArray().apply { labels.forEach { name -> add(name) } })
        }

        return UniversalRecord(
            platform = platform,
            recordType = type,
            sourceApp = raw.hostApp,
            platformId = "github_${owner}_${repo}_${type}_$suffix",
            title = node.str("title").ifBlank { node.str("name") },
            content = node.str("body"),
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            authorId = author?.str("login").orEmpty(),
            authorName = author?.str("login").orEmpty(),
            authorAvatar = author?.str("avatar_url").orEmpty(),
            sourceUrl = node.str("html_url").ifBlank { "https://github.com/$owner/$repo" },
            publishTime = parseTime(node.str("created_at")),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun commentRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val match = COMMENT_PATH.find(cleanUrl) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val number = match.groupValues[3]
        val commentId = node["id"]?.asString.orEmpty()
        if (commentId.isEmpty()) return null
        val author = node.obj("user")
        return UniversalRecord(
            platform = platform,
            recordType = "comment",
            sourceApp = raw.hostApp,
            platformId = "github_comment_$commentId",
            parentId = "github_${owner}_${repo}_issue_$number",
            content = node.str("body"),
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            authorId = author?.str("login").orEmpty(),
            authorName = author?.str("login").orEmpty(),
            authorAvatar = author?.str("avatar_url").orEmpty(),
            sourceUrl = node.str("html_url"),
            publishTime = parseTime(node.str("created_at")),
            captureTime = raw.timestamp,
            extraMetadata = JsonObject().apply {
                addProperty("repo", "$owner/$repo")
                addProperty("issue", number)
            }.toString()
        )
    }

    private fun graphqlRecord(node: JsonObject, raw: RawData, type: String): UniversalRecord? {
        val number = node["number"]?.asLong ?: return null
        val repoName = node.obj("repository")?.str("nameWithOwner").orEmpty()
        if ('/' !in repoName) return null
        val owner = repoName.substringBefore('/')
        val repo = repoName.substringAfter('/')
        val author = node.obj("author")
        val labels = node.obj("labels")?.array("nodes")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.str("name") }
            .orEmpty()
        val meta = JsonObject().apply {
            addProperty("repo", repoName)
            addProperty("number", number)
            addProperty("state", node.str("state"))
            node.obj("comments")?.let { addProperty("comments", it["totalCount"]?.asLong ?: 0L) }
            if (labels.isNotEmpty()) add("labels", JsonArray().apply { labels.forEach { name -> add(name) } })
        }
        return UniversalRecord(
            platform = platform,
            recordType = type,
            sourceApp = raw.hostApp,
            platformId = "github_${owner}_${repo}_${type}_$number",
            title = node.str("title"),
            content = node.str("body"),
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            authorId = author?.str("login").orEmpty(),
            authorName = author?.str("login").orEmpty(),
            authorAvatar = author?.str("avatarUrl").orEmpty(),
            sourceUrl = node.str("url"),
            publishTime = parseTime(node.str("createdAt")),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun readmeRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val repo = ownRepo(cleanUrl) ?: return null
        val content = decodeBase64(node.str("content")).takeIf { it.isNotBlank() } ?: return null
        return UniversalRecord(
            platform = platform,
            recordType = "readme",
            sourceApp = raw.hostApp,
            platformId = "github_${repo.first}_${repo.second}_readme",
            title = "${repo.first}/${repo.second} README",
            content = content,
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            authorName = repo.first,
            sourceUrl = node.str("html_url").ifBlank { "https://github.com/${repo.first}/${repo.second}" },
            captureTime = raw.timestamp
        )
    }

    private fun ownRepo(url: String): Pair<String, String>? {
        val match = REST_PATH.find(url) ?: COMMENT_PATH.find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun decodeBase64(value: String): String = runCatching {
        String(android.util.Base64.decode(value.replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault("")

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
