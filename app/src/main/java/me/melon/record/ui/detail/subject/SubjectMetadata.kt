package me.melon.record.ui.detail.subject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.safeDouble
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import me.melon.record.parser.douban.DoubanUtils.safeStringList

internal class SubjectMetadata(
    val meta: JsonObject,
    val subjectTitle: String,
    val originalTitle: String,
    val subtype: String,
    val year: String,
    val coverUrl: String,
    val intro: String,
    val cardSubtitle: String,
    val ratingVal: Double,
    val ratingStars: Double,
    val ratingCount: Long,
    val genres: List<String>,
    val countries: List<String>,
    val directors: List<String>,
    val actors: List<String>,
    val writers: List<String>,
    val pubdate: List<String>,
    val durations: List<String>,
    val languages: List<String>,
    val episodesCount: Long,
    val author: List<String>,
    val translator: List<String>,
    val press: List<String>,
    val pages: List<String>,
    val price: List<String>,
    val binding: String,
    val isbn13: String,
    val developers: List<String>,
    val publishers: List<String>,
    val platforms: List<String>,
    val releaseDate: String,
    val singer: List<String>,
    val discs: List<String>,
    val honors: List<String>,
    val tags: List<String>,
    val subtypeLabel: String
)

@Composable
internal fun rememberSubjectMetadata(currentRecord: UniversalRecord): SubjectMetadata {
    val meta = remember(currentRecord.extraMetadata) {
        try {
            JsonParser.parseString(currentRecord.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    val subjectTitle = remember(currentRecord.title, meta) {
        meta.safeString("subject_title").ifBlank { currentRecord.title }
    }
    val originalTitle = remember(meta) { meta.safeString("original_title") }
    val subtype = remember(meta) { meta.safeString("subject_type").ifBlank { "movie" } }
    val year = remember(meta) { meta.safeString("year") }
    val coverUrl = remember(meta) { meta.safeString("cover_url").ifBlank { meta.safeString("title_image") } }
    val intro = remember(currentRecord.content, meta) {
        meta.safeString("intro").ifBlank { currentRecord.content }
    }
    val cardSubtitle = remember(meta) { meta.safeString("card_subtitle") }

    val ratingVal = remember(meta) { meta.safeDouble("rating_value") }
    val ratingStars = remember(meta) { meta.safeDouble("rating_stars") }
    val ratingCount = remember(meta) { meta.safeLong("rating_count") }

    val genres = remember(meta) { meta.safeStringList("genres") }
    val countries = remember(meta) { meta.safeStringList("countries") }
    val directors = remember(meta) { meta.safeStringList("directors") }
    val actors = remember(meta) { meta.safeStringList("actors") }
    val writers = remember(meta) { meta.safeStringList("writers") }
    val pubdate = remember(meta) { meta.safeStringList("pubdate") }
    val durations = remember(meta) { meta.safeStringList("durations") }
    val languages = remember(meta) { meta.safeStringList("languages") }
    val episodesCount = remember(meta) { meta.safeLong("episodes_count") }

    val author = remember(meta) { meta.safeStringList("author") }
    val translator = remember(meta) { meta.safeStringList("translator") }
    val press = remember(meta) { meta.safeStringList("press") }
    val pages = remember(meta) { meta.safeStringList("pages") }
    val price = remember(meta) { meta.safeStringList("price") }
    val binding = remember(meta) { meta.safeString("binding") }
    val isbn13 = remember(meta) { meta.safeString("isbn13") }

    val developers = remember(meta) { meta.safeStringList("developers") }
    val publishers = remember(meta) { meta.safeStringList("publishers") }
    val platforms = remember(meta) { meta.safeStringList("platforms") }
    val releaseDate = remember(meta) { meta.safeString("release_date") }

    val singer = remember(meta) { meta.safeStringList("singer") }
    val discs = remember(meta) { meta.safeStringList("discs") }

    val honors = remember(meta) { meta.safeStringList("honor_infos") }
    val tags = remember(meta) { meta.safeStringList("tags") }

    val subtypeLabel = when (subtype) {
        "movie" -> "电影"
        "book" -> "图书"
        "music" -> "音乐"
        "game" -> "游戏"
        "tv" -> "电视剧"
        "drama" -> "舞台剧"
        else -> "条目"
    }
    return SubjectMetadata(
        meta = meta,
        subjectTitle = subjectTitle,
        originalTitle = originalTitle,
        subtype = subtype,
        year = year,
        coverUrl = coverUrl,
        intro = intro,
        cardSubtitle = cardSubtitle,
        ratingVal = ratingVal,
        ratingStars = ratingStars,
        ratingCount = ratingCount,
        genres = genres,
        countries = countries,
        directors = directors,
        actors = actors,
        writers = writers,
        pubdate = pubdate,
        durations = durations,
        languages = languages,
        episodesCount = episodesCount,
        author = author,
        translator = translator,
        press = press,
        pages = pages,
        price = price,
        binding = binding,
        isbn13 = isbn13,
        developers = developers,
        publishers = publishers,
        platforms = platforms,
        releaseDate = releaseDate,
        singer = singer,
        discs = discs,
        honors = honors,
        tags = tags,
        subtypeLabel = subtypeLabel
    )
}
