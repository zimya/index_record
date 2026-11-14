package com.melonhu.indexrecord.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Immutable subject values; neither the source entity nor parsed JSON is retained. */
internal class DoubanSubjectMetadata(record: DetailRecord) {
    val subjectTitle: String
    val originalTitle: String
    val subtype: String
    val year: String
    val coverUrl: String
    val intro: String
    val cardSubtitle: String
    val headerBackgroundColor: String
    val ratingVal: Double
    val ratingStars: Double
    val ratingCount: Long
    val genres: List<String>
    val countries: List<String>
    val directors: List<String>
    val actors: List<String>
    val writers: List<String>
    val pubdate: List<String>
    val durations: List<String>
    val languages: List<String>
    val episodesCount: Long
    val author: List<String>
    val translator: List<String>
    val press: List<String>
    val pages: List<String>
    val price: List<String>
    val binding: String
    val isbn13: String
    val developers: List<String>
    val publishers: List<String>
    val platforms: List<String>
    val releaseDate: String
    val singer: List<String>
    val discs: List<String>
    val honors: List<String>
    val tags: List<String>
    val subtypeLabel: String

    init {
        val values = parseMetadata(record.extraMetadata)
        subjectTitle = values.text("subject_title").ifBlank { record.title }
        originalTitle = values.text("original_title")
        subtype = values.text("subject_type").ifBlank { "movie" }
        year = values.text("year")
        coverUrl = values.text("cover_url").ifBlank { values.text("title_image") }
        intro = values.text("intro").ifBlank { record.content }
        cardSubtitle = values.text("card_subtitle")
        headerBackgroundColor = values.text("header_bg_color")
        ratingVal = values.decimal("rating_value")
        ratingStars = values.decimal("rating_stars")
        ratingCount = values.integer("rating_count")
        genres = values.strings("genres")
        countries = values.strings("countries")
        directors = values.strings("directors")
        actors = values.strings("actors")
        writers = values.strings("writers")
        pubdate = values.strings("pubdate")
        durations = values.strings("durations")
        languages = values.strings("languages")
        episodesCount = values.integer("episodes_count")
        author = values.strings("author")
        translator = values.strings("translator")
        press = values.strings("press")
        pages = values.strings("pages")
        price = values.strings("price")
        binding = values.text("binding")
        isbn13 = values.text("isbn13")
        developers = values.strings("developers")
        publishers = values.strings("publishers")
        platforms = values.strings("platforms")
        releaseDate = values.text("release_date")
        singer = values.strings("singer")
        discs = values.strings("discs")
        honors = values.strings("honor_infos")
        tags = values.strings("tags")
        subtypeLabel = when (subtype) {
            "movie" -> "电影"
            "book" -> "图书"
            "music" -> "音乐"
            "game" -> "游戏"
            "tv" -> "电视剧"
            "drama" -> "舞台剧"
            else -> "条目"
        }
    }
}

internal class DoubanSubjectRelatedMetadata(record: DetailRecord) {
    val starCount: Double
    val voteCount: Long

    init {
        val values = parseMetadata(record.extraMetadata)
        starCount = values.decimal("star_count")
        voteCount = values.integer("voteup_count")
    }
}

private fun parseMetadata(json: String): JsonObject =
    runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { JsonObject() }

private fun JsonObject.strings(key: String): List<String> {
    val value = get(key) ?: return immutableList(emptyList())
    val strings = when {
        value.isJsonPrimitive -> listOf(value.asString.trim()).filter { it.isNotBlank() }
        value.isJsonArray -> value.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString.trim()
                item.isJsonObject -> item.asJsonObject.let {
                    it.text("name").ifBlank { it.text("title") }.ifBlank { it.text("cn_name") }
                }
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
        else -> emptyList()
    }
    return immutableList(strings)
}

private fun JsonObject.integer(key: String): Long = runCatching {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    when {
        value == null -> 0L
        value.isNumber -> value.asLong
        else -> value.asString.toLongOrNull() ?: 0L
    }
}.getOrDefault(0L)

private fun JsonObject.decimal(key: String): Double = runCatching {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    when {
        value == null -> 0.0
        value.isNumber -> value.asDouble
        else -> value.asString.toDoubleOrNull() ?: 0.0
    }
}.getOrDefault(0.0)

private fun JsonObject.text(key: String): String {
    val value = get(key)?.takeUnless { it.isJsonNull } ?: return ""
    return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
}
