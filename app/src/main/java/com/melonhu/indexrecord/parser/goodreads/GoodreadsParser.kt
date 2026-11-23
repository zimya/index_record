package com.melonhu.indexrecord.parser.goodreads

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Goodreads 书评与书目页存档。
 */
object GoodreadsParser : RawDataParser {
    override val platform = "goodreads"
    const val HOST_APP = "com.goodreads"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        host == "goodreads.com" || host.endsWith(".goodreads.com")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Goodreads endpoint")
        val record = fromHtml(raw, cleanUrl) ?: return@attempt ParseResult.Success(emptyList())
        ParseResult.Success(listOf(record))
    }

    private fun fromHtml(raw: RawData, cleanUrl: String): UniversalRecord? {
        val doc = Jsoup.parse(raw.json, cleanUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val review = doc.selectFirst("div.reviewText")?.html().orEmpty()
        val description = doc.selectFirst("div#description")?.html().orEmpty()
        val body = review.ifBlank { description }
        if (title.isEmpty() && body.isEmpty()) return null
        val isReview = review.isNotBlank()
        val id = urlId(cleanUrl, if (isReview) "review" else "book")
        if (id.isEmpty()) return null
        val rating = doc.selectFirst("span[itemprop=ratingValue]")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=books:rating:value]")?.attr("content").orEmpty() }
        val author = doc.selectFirst("a.authorName span[itemprop=name]")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[name=author]")?.attr("content").orEmpty() }
        return UniversalRecord(
            platform = platform,
            recordType = if (isReview) "review" else "book",
            sourceApp = raw.hostApp,
            platformId = "goodreads_${if (isReview) "review" else "book"}_$id",
            title = title,
            content = body,
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorName = author,
            authorAvatar = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
            sourceUrl = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { cleanUrl },
            publishTime = parseTime(
                doc.selectFirst("meta[property=article:published_time]")?.attr("content").orEmpty()
            ),
            captureTime = raw.timestamp,
            extraMetadata = com.google.gson.JsonObject().apply {
                rating.toDoubleOrNull()?.let { addProperty("rating", it) }
                if (isReview) addProperty("kind", "review") else addProperty("kind", "book")
            }.toString()
        )
    }

    private fun urlId(url: String, kind: String): String {
        if (kind == "review") {
            Regex("""/review/show/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        }
        return Regex("""/book/show/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/show/(\d+)""").find(url)?.groupValues?.get(1)
            ?: ""
    }

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }
}
