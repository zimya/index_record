package me.melon.record.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TweetRecordMode {
    PUSH,
    READ;

    fun shouldProcess(url: String): Boolean = this == PUSH || url.contains("ConversationTimeline")

    companion object {
        fun fromStoredValue(value: Any?): TweetRecordMode =
            entries.firstOrNull { it.name == value } ?: READ
    }
}

enum class WeiboRecordMode {
    PUSH,
    READ;

    fun shouldProcess(url: String): Boolean {
        if (this == PUSH) return true
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
        // 信息流及批量长文接口也会预加载正文，不能作为点进详情的依据。
        return path in setOf(
            "/2/statuses/container_detail",
            "/2/statuses/container_detail_comment",
            "/2/comments/build_comments",
            "/2/statuses/show",
            "/statuses/show"
        )
    }

    companion object {
        fun fromStoredValue(value: Any?): WeiboRecordMode =
            entries.firstOrNull { it.name == value } ?: READ
    }
}

enum class ThreadsRecordMode {
    PUSH,
    READ;

    fun shouldProcess(url: String): Boolean {
        if (this == PUSH) return true
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
        // 仅接受详情/回复入口；首页、资料页及通用 media/GraphQL 接口可能预加载帖子。
        return Regex("^/api/v1/text_feed/[0-9]+/replies_streaming/?$").matches(path) ||
            path.trimEnd('/') == "/api/v1/feed/text_post_app_replies"
    }

    companion object {
        fun fromStoredValue(value: Any?): ThreadsRecordMode =
            entries.firstOrNull { it.name == value } ?: READ
    }
}

enum class RedditRecordMode {
    PUSH,
    READ;

    companion object {
        fun fromStoredValue(value: Any?): RedditRecordMode =
            entries.firstOrNull { it.name == value } ?: READ
    }
}

object RecordSettings {
    private const val TWEET_RECORD_MODE = "tweet_record_mode"
    private const val WEIBO_RECORD_MODE = "weibo_record_mode"
    private const val THREADS_RECORD_MODE = "threads_record_mode"
    private const val REDDIT_RECORD_MODE = "reddit_record_mode"
    private const val INCLUDE_REPLIES_AND_COMMENTS_IN_STATS = "include_replies_and_comments_in_stats"
    private val _includeRepliesAndCommentsInStats = MutableStateFlow(false)
    val includeRepliesAndCommentsInStats = _includeRepliesAndCommentsInStats.asStateFlow()
    private const val COLLAPSE_MULTIPLE_ITEMS = "collapse_multiple_items"
    private val _collapseMultipleItems = MutableStateFlow(true)
    val collapseMultipleItems = _collapseMultipleItems.asStateFlow()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _collapseMultipleItems.value = prefs.getBoolean(COLLAPSE_MULTIPLE_ITEMS, true)
        _includeRepliesAndCommentsInStats.value = prefs.getBoolean(INCLUDE_REPLIES_AND_COMMENTS_IN_STATS, false)
    }

    fun setIncludeRepliesAndCommentsInStats(enabled: Boolean) {
        prefs.edit().putBoolean(INCLUDE_REPLIES_AND_COMMENTS_IN_STATS, enabled).apply()
        _includeRepliesAndCommentsInStats.value = enabled
    }

    fun setCollapseMultipleItems(enabled: Boolean) {
        prefs.edit().putBoolean(COLLAPSE_MULTIPLE_ITEMS, enabled).apply()
        _collapseMultipleItems.value = enabled
    }

    var redditRecordMode: RedditRecordMode
        get() = RedditRecordMode.fromStoredValue(prefs.all[REDDIT_RECORD_MODE])
        set(value) {
            prefs.edit().putString(REDDIT_RECORD_MODE, value.name).apply()
        }

    var threadsRecordMode: ThreadsRecordMode
        get() = ThreadsRecordMode.fromStoredValue(prefs.all[THREADS_RECORD_MODE])
        set(value) {
            prefs.edit().putString(THREADS_RECORD_MODE, value.name).apply()
        }

    var weiboRecordMode: WeiboRecordMode
        get() = WeiboRecordMode.fromStoredValue(prefs.all[WEIBO_RECORD_MODE])
        set(value) {
            prefs.edit().putString(WEIBO_RECORD_MODE, value.name).apply()
        }

    var tweetRecordMode: TweetRecordMode
        // 旧安装可能保存了整数；按原始类型读取，未知值安全回退到阅读。
        get() = TweetRecordMode.fromStoredValue(prefs.all[TWEET_RECORD_MODE])
        set(value) {
            prefs.edit().putString(TWEET_RECORD_MODE, value.name).apply()
        }
}
