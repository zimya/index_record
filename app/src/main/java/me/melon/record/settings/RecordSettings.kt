package me.melon.record.settings

import android.content.Context
import android.content.SharedPreferences

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

object RecordSettings {
    private const val TWEET_RECORD_MODE = "tweet_record_mode"
        private const val WEIBO_RECORD_MODE = "weibo_record_mode"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
