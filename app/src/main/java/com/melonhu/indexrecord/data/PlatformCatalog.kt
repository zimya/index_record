package com.melonhu.indexrecord.data

import com.melonhu.indexrecord.R

/** Shared platform vocabulary. Capture protocols and UI colors deliberately stay outside this catalog. */
object PlatformCatalog {
    data class Platform(val id: String, val name: String, val defaultPackage: String)

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
        Platform("github", "GitHub", "com.github.android"),
        Platform("mastodon", "Mastodon", "org.joinmastodon.android"),
        Platform("bilibili", "哔哩哔哩", "tv.danmaku.bili"),
        Platform("medium", "Medium", "com.medium.reader"),
        Platform("goodreads", "Goodreads", "com.goodreads"),
        Platform("lofter", "LOFTER", "com.lofter.android"),
        Platform("dcard", "Dcard", "com.dcard.app"),
        Platform("weread", "微信读书", "com.tencent.weread"),
        Platform("netease", "网易云音乐", "com.netease.cloudmusic")
    )
    private val byId = platforms.associateBy { it.id }
    private val packageNames = platforms.associate { it.defaultPackage to it.name } +
        ("com.weico.international" to "微博国际版") +
        ("com.github.zly2006.zhplus.lite" to "知乎++") +
        ("com.github.zly2006.zhplus" to "知乎++")
    val hostPackages: List<String> = packageNames.keys.toList()

    fun displayName(platform: String): String = byId[platform]?.name
        ?: platform.trim().ifEmpty { "平台未知" }

    fun defaultPackage(platform: String): String = byId[platform]?.defaultPackage.orEmpty()

    fun packageLabel(packageName: String): String? = packageNames[packageName]
    fun knownDisplayName(platform: String): String? = byId[platform]?.name

    fun platformForPackage(packageName: String): String? {
        if (packageName == "com.weico.international") return "weibo"
        return platforms.firstOrNull { it.defaultPackage.equals(packageName, ignoreCase = true) }?.id
            ?: byId[packageName]?.id
    }

    private val typeNames = mapOf(
        "question" to "问题", "answer" to "回答", "article" to "文章", "pin" to "想法",
        "comment" to "评论", "lou" to "主帖", "thread" to "帖子", "floor" to "楼层",
        "topic" to "帖子", "post" to "帖子", "review" to "长评", "interest" to "短评",
        "subject" to "条目", "tweet" to "推文", "status" to "微博",
        "feed" to "动态", "rating" to "点评", "feedArticle" to "图文",
        "issue" to "Issue", "pr" to "PR", "discussion" to "讨论", "release" to "版本",
        "readme" to "README", "opus" to "图文", "note" to "想法", "book" to "书籍",
        "song" to "歌曲", "video" to "视频"
    )

    fun typeLabel(platform: String, type: String): String = when {
        type == "comment" && platform == "tieba" -> "楼中楼"
        type == "comment" && platform in setOf(
            "reddit", "threads", "hupu", "xiaoheihe", "bluesky", "nga", "github", "mastodon"
        ) -> "回复"
        else -> typeNames[type] ?: type.trim().ifEmpty { "类型未知" }
    }

    fun recordLabel(platform: String, type: String): String =
        "${displayName(platform)} · ${typeLabel(platform, type)}"

    // Filters identify the source as well as the type; list cards already show a platform badge.
    fun filterTypeLabel(type: String): String = when (type) {
        "question", "answer", "article", "pin" -> "知乎${typeLabel("zhihu", type)}"
        "topic", "review", "interest", "subject" -> "豆瓣${typeLabel("douban", type)}"
        "post" -> "Reddit 帖子"
        "lou", "floor" -> "贴吧${typeLabel("tieba", type)}"
        "thread" -> "Threads 帖子"
        "tweet" -> "X 推文"
        "feed", "rating", "feedArticle" -> "酷安${typeLabel("coolapk", type)}"
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
        "github" -> R.string.platform_github
        "mastodon" -> R.string.platform_mastodon
        "bilibili" -> R.string.platform_bilibili
        "medium" -> R.string.platform_medium
        "goodreads" -> R.string.platform_goodreads
        "lofter" -> R.string.platform_lofter
        "dcard" -> R.string.platform_dcard
        "weread" -> R.string.platform_weread
        "netease" -> R.string.platform_netease
        else -> null
    }

    fun typeNameRes(platform: String, type: String): Int? = when {
        type == "comment" && platform == "tieba" -> R.string.type_subfloor
        type == "comment" && platform in setOf(
            "reddit", "threads", "hupu", "xiaoheihe", "bluesky", "nga", "github", "mastodon"
        ) -> R.string.type_reply
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
        type == "tweet" -> R.string.type_tweet
        type == "status" -> R.string.type_status
        type == "feed" -> R.string.type_feed
        type == "rating" -> R.string.type_rating
        type == "feedArticle" -> R.string.type_feed_article
        type == "issue" -> R.string.type_issue
        type == "pr" -> R.string.type_pr
        type == "discussion" -> R.string.type_discussion
        type == "release" -> R.string.type_release
        type == "readme" -> R.string.type_readme
        type == "opus" -> R.string.type_opus
        type == "note" -> R.string.type_note
        type == "book" -> R.string.type_book
        type == "song" -> R.string.type_song
        type == "video" -> R.string.type_video
        else -> null
    }
}
