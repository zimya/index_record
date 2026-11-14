package com.melonhu.indexrecord.data

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
        Platform("weibo", "微博", "com.sina.weibo")
    )
    private val byId = platforms.associateBy { it.id }
    private val packageNames = platforms.associate { it.defaultPackage to it.name } +
        ("com.weico.international" to "微博国际版")
    val hostPackages: List<String> = packageNames.keys.toList()

    fun displayName(platform: String): String = byId[platform]?.name
        ?: platform.trim().ifEmpty { "平台未知" }

    fun defaultPackage(platform: String): String = byId[platform]?.defaultPackage.orEmpty()

    fun packageLabel(packageName: String): String? = packageNames[packageName]
    fun knownDisplayName(platform: String): String? = byId[platform]?.name

    private val typeNames = mapOf(
        "question" to "问题", "answer" to "回答", "article" to "文章", "pin" to "想法",
        "comment" to "评论", "lou" to "主帖", "thread" to "帖子", "floor" to "楼层",
        "topic" to "帖子", "post" to "帖子", "review" to "长评", "interest" to "短评",
        "subject" to "条目", "tweet" to "推文", "status" to "微博"
    )

    fun typeLabel(platform: String, type: String): String = when {
        type == "comment" && platform == "tieba" -> "楼中楼"
        type == "comment" && platform in setOf("reddit", "threads") -> "回复"
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
        else -> typeLabel("", type)
    }
}
