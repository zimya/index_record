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

object RecordSettings {
    private const val TWEET_RECORD_MODE = "tweet_record_mode"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var tweetRecordMode: TweetRecordMode
        // 旧安装可能保存了整数；按原始类型读取，未知值安全回退到阅读。
        get() = TweetRecordMode.fromStoredValue(prefs.all[TWEET_RECORD_MODE])
        set(value) {
            prefs.edit().putString(TWEET_RECORD_MODE, value.name).apply()
        }
}
