package com.melonhu.indexrecord.data

import com.melonhu.indexrecord.R

/** Shared platform vocabulary. Capture protocols and UI colors deliberately stay outside this catalog. */
object PlatformCatalog {
    data class Platform(val id: String, val name: String, val defaultPackage: String)

    internal const val BILIBILI_PACKAGE = "tv.danmaku.bili"
    internal const val BILIBILI_INTERNATIONAL_PACKAGE = "com.bilibili.app.in"
    internal const val NETEASE_PACKAGE = "com.netease.cloudmusic"
    internal const val ZHIHU_PLUS_PACKAGE = "com.github.zly2006.zhplus"
    internal const val ZHIHU_PLUS_LITE_PACKAGE = "com.github.zly2006.zhplus.lite"
    internal const val TIEBA_LITE_PACKAGE = "com.huanchengfly.tieba.post"

    val platforms: List<Platform> = listOf(
        Platform("zhihu", "知乎", "com.zhihu.android"),
        Platform("douban", "豆瓣", "com.douban.frodo"),
        Platform("tieba", "贴吧", "com.baidu.tieba"),
        Platform("twitter", "X", "com.twitter.android"),
        Platform("threads", "Threads", "com.instagram.barcelona"),
        Platform("reddit", "Reddit", "com.reddit.frontpage"),
        Platform("weibo", "微博", "com.sina.weibo"),
        Platform("coolapk", "酷安", "com.coolapk.market"),
        Platform("hupu", "虎扑", "com.hupu.games"),
        Platform("xiaoheihe", "小黑盒", "com.max.xiaoheihe"),
        Platform("bluesky", "Bluesky", "xyz.blueskyweb.app"),
        Platform("nga", "NGA", "gov.pianzong.androidnga"),
        Platform("bilibili", "Bilibili", BILIBILI_PACKAGE),
        Platform("netease", "网易云音乐", NETEASE_PACKAGE)
    )
    private val byId = platforms.associateBy { it.id }
    private val packageNames = platforms.associate { it.defaultPackage to it.name } + mapOf(
        "com.weico.international" to "微博国际版",
        BILIBILI_INTERNATIONAL_PACKAGE to "Bilibili 国际版",
        ZHIHU_PLUS_PACKAGE to "知乎++",
        ZHIHU_PLUS_LITE_PACKAGE to "知乎++ Lite",
        TIEBA_LITE_PACKAGE to "Tieba Lite"
    )
    val hostPackages: List<String> = packageNames.keys.toList()

    fun displayName(platform: String): String = byId[platform]?.name
        ?: platform.trim().ifEmpty { "平台未知" }

    fun defaultPackage(platform: String): String = byId[platform]?.defaultPackage.orEmpty()

    fun packageLabel(packageName: String): String? = packageNames[packageName]
    fun knownDisplayName(platform: String): String? = byId[platform]?.name

    fun platformForPackage(packageName: String): String? {
        if (packageName == "com.weico.international") return "weibo"
        if (packageName.equals(BILIBILI_INTERNATIONAL_PACKAGE, ignoreCase = true)) return "bilibili"
        if (packageName.equals(ZHIHU_PLUS_PACKAGE, ignoreCase = true) ||
            packageName.equals(ZHIHU_PLUS_LITE_PACKAGE, ignoreCase = true)
        ) return "zhihu"
        if (packageName.equals(TIEBA_LITE_PACKAGE, ignoreCase = true)) return "tieba"
        return platforms.firstOrNull { it.defaultPackage.equals(packageName, ignoreCase = true) }?.id
            ?: byId[packageName]?.id
    }

    private val typeNames = mapOf(
        "question" to "问题", "answer" to "回答", "article" to "文章", "pin" to "想法",
        "comment" to "评论", "lou" to "主帖", "thread" to "帖子", "floor" to "楼层",
        "topic" to "帖子", "post" to "帖子", "review" to "长评", "interest" to "短评",
        "subject" to "条目", "note" to "日记", "tweet" to "推文", "status" to "微博",
        "feed" to "动态", "rating" to "点评", "feedArticle" to "图文", "video" to "视频", "opus" to "图文",
        "song" to "歌曲", "playlist" to "歌单", "album" to "专辑", "program" to "播客"
    )

    fun typeLabel(platform: String, type: String): String = when {
        type == "comment" && platform == "tieba" -> "楼中楼"
        type == "comment" && platform in setOf("reddit", "threads", "hupu", "xiaoheihe", "bluesky", "nga") -> "回复"
        else -> typeNames[type] ?: type.trim().ifEmpty { "类型未知" }
    }

    fun recordLabel(platform: String, type: String): String =
        "${displayName(platform)} · ${typeLabel(platform, type)}"

    // Filters identify the source as well as the type; list cards already show a platform badge.
    fun filterTypeLabel(type: String): String = when (type) {
        "question", "answer", "article", "pin" -> "知乎${typeLabel("zhihu", type)}"
        "topic", "review", "interest", "subject", "note" -> "豆瓣${typeLabel("douban", type)}"
        "post" -> "Reddit 帖子"
        "lou", "floor" -> "贴吧${typeLabel("tieba", type)}"
        "thread" -> "Threads 帖子"
        "tweet" -> "X 推文"
        "feed", "rating", "feedArticle" -> "酷安${typeLabel("coolapk", type)}"
        "video", "opus" -> "Bilibili${typeLabel("bilibili", type)}"
        "song", "playlist", "album", "program" -> "网易云音乐${typeLabel("netease", type)}"
        else -> typeLabel("", type)
    }

    fun platformNameRes(platform: String): Int? = when (platform.lowercase()) {
        "zhihu" -> R.string.platform_zhihu
        "douban" -> R.string.platform_douban
        "tieba" -> R.string.platform_tieba
        "twitter" -> R.string.platform_twitter
        "threads" -> R.string.platform_threads
        "reddit" -> R.string.platform_reddit
        "weibo" -> R.string.platform_weibo
        "coolapk" -> R.string.platform_coolapk
        "hupu" -> R.string.platform_hupu
        "xiaoheihe" -> R.string.platform_xiaoheihe
        "bluesky" -> R.string.platform_bluesky
        "nga" -> R.string.platform_nga
        "bilibili" -> R.string.platform_bilibili
        "netease" -> R.string.platform_netease
        else -> null
    }

    fun typeNameRes(platform: String, type: String): Int? = when {
        type == "comment" && platform == "tieba" -> R.string.type_subfloor
        type == "comment" && platform in setOf("reddit", "threads", "hupu", "xiaoheihe", "bluesky", "nga") -> R.string.type_reply
        type == "question" -> R.string.type_question
        type == "answer" -> R.string.type_answer
        type == "article" -> R.string.type_article
        type == "pin" -> R.string.type_pin
        type == "comment" -> R.string.type_comment
        type == "lou" -> R.string.type_lou
        type == "thread" -> R.string.type_thread
        type == "floor" -> R.string.type_floor
        type == "topic" -> R.string.type_topic
        type == "post" -> R.string.type_post
        type == "review" -> R.string.type_review
        type == "interest" -> R.string.type_interest
        type == "subject" -> R.string.type_subject
        type == "note" -> R.string.type_note
        type == "tweet" -> R.string.type_tweet
        type == "status" -> R.string.type_status
        type == "feed" -> R.string.type_feed
        type == "rating" -> R.string.type_rating
        type == "feedArticle" -> R.string.type_feed_article
        type == "video" -> R.string.type_video
        type == "opus" -> R.string.type_opus
        type == "song" -> R.string.type_song
        type == "playlist" -> R.string.type_playlist
        type == "album" -> R.string.type_album
        type == "program" -> R.string.type_program
        else -> null
    }
}
