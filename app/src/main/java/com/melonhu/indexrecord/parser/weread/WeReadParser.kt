package com.melonhu.indexrecord.parser.weread

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI

/**
 * 微信读书书目、想法与书评存档。
 */
object WeReadParser : RawDataParser {
    override val platform = "weread"
    const val HOST_APP = "com.tencent.weread"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        host == "weread.qq.com" || host.endsWith(".weread.qq.com")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported WeRead endpoint")
        val root = JsonParser.parseString(raw.json).asJsonObject
        val records = linkedMapOf<String, UniversalRecord>()

        val book = findBook(root)
        val reviews = findReviews(root)
        if (reviews.isEmpty()) {
            book?.let { b -> bookRecord(b, raw)?.let { records[it.platformId] = it } }
        }
        reviews.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val record = reviewRecord(el.asJsonObject, book, raw) ?: return@forEach
            records[record.platformId] = record
        }

        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun findBook(root: JsonObject): JsonObject? =
        root.obj("bookInfo") ?: root.obj("book") ?: root.obj("data")?.let { data ->
            data.obj("bookInfo") ?: data.obj("book")
        }

    private fun findReviews(root: JsonObject): List<com.google.gson.JsonElement> {
        val direct = root.array("reviews")
        if (direct.size() > 0) return direct.map { it }
        val nested = root.obj("data")?.array("reviews") ?: JsonArray()
        if (nested.size() > 0) return nested.map { it }
        root.obj("review")?.let { return listOf(it) }
        if (root.has("reviewId") || root.has("content")) return listOf(root)
        return emptyList()
    }

    private fun bookRecord(book: JsonObject, raw: RawData): UniversalRecord? {
        val id = book.str("bookId").ifBlank { book.str("id") }
        if (id.isEmpty()) return null
        return UniversalRecord(
            platform = platform,
            recordType = "book",
            sourceApp = raw.hostApp,
            platformId = "weread_book_$id",
            title = book.str("title"),
            content = book.str("intro").ifBlank { book.str("description") },
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorId = book.str("authorId"),
            authorName = book.str("author"),
            authorAvatar = book.str("cover"),
            sourceUrl = "https://weread.qq.com/web/bookDetail/$id",
            captureTime = raw.timestamp
        )
    }

    private fun reviewRecord(node: JsonObject, book: JsonObject?, raw: RawData): UniversalRecord? {
        val review = node.obj("review") ?: node
        val id = review.str("reviewId").ifBlank { review.str("id") }
        if (id.isEmpty()) return null
        val bookInfo = review.obj("bookInfo") ?: book
        val bookId = review.str("bookId").ifBlank { bookInfo?.str("bookId").orEmpty() }
        val chapter = review.str("chapterTitle").ifBlank { review.str("chapterName") }
        val title = listOfNotNull(
            bookInfo?.str("title")?.takeIf { it.isNotBlank() },
            chapter.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        return UniversalRecord(
            platform = platform,
            recordType = if (review.str("star").isNotBlank()) "review" else "note",
            sourceApp = raw.hostApp,
            platformId = "weread_review_$id",
            parentId = if (bookId.isNotEmpty()) "weread_book_$bookId" else "",
            title = title,
            content = review.str("content"),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorId = review.str("userVid").ifBlank { review.str("userId") },
            authorName = review.str("userName").ifBlank { review.obj("user")?.str("name").orEmpty() },
            authorAvatar = review.obj("user")?.str("avatar").orEmpty(),
            sourceUrl = "https://weread.qq.com/web/bookDetail/$bookId",
            publishTime = epochMillis(review.str("createTime").ifBlank { review.str("updateTime") }),
            captureTime = raw.timestamp,
            extraMetadata = JsonObject().apply {
                review.str("star").takeIf { it.isNotBlank() }?.let { addProperty("rating", it) }
                chapter.takeIf { it.isNotBlank() }?.let { addProperty("chapter", it) }
                review["likeCount"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
            }.toString()
        )
    }

    private fun epochMillis(value: String): Long {
        val number = value.toLongOrNull() ?: return 0L
        return if (number > 10_000_000_000L) number else number * 1000L
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
