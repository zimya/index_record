package com.melonhu.indexrecord.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.settings.BlueskyRecordMode
import com.melonhu.indexrecord.settings.RedditRecordMode
import com.melonhu.indexrecord.settings.ThreadsRecordMode
import com.melonhu.indexrecord.settings.TweetRecordMode
import com.melonhu.indexrecord.settings.WeiboRecordMode
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

@Composable
internal fun RecordSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val tweetRecordMode by viewModel.tweetRecordMode.collectAsState()
    val weiboRecordMode by viewModel.weiboRecordMode.collectAsState()
    val threadsRecordMode by viewModel.threadsRecordMode.collectAsState()
    val redditRecordMode by viewModel.redditRecordMode.collectAsState()
    val blueskyRecordMode by viewModel.blueskyRecordMode.collectAsState()

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_record),
        onBack = onBack
    ) {
        val modePushLabel = stringResource(R.string.settings_mode_push)
        val modeReadLabel = stringResource(R.string.settings_mode_read)

        SettingsDropdownRow(
            title = stringResource(R.string.settings_tweet_record_mode),
            selected = tweetRecordMode,
            options = listOf(TweetRecordMode.PUSH, TweetRecordMode.READ),
            label = { if (it == TweetRecordMode.PUSH) modePushLabel else modeReadLabel },
            onSelect = viewModel::setTweetRecordMode
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_weibo_record_mode),
            selected = weiboRecordMode,
            options = listOf(WeiboRecordMode.PUSH, WeiboRecordMode.READ),
            label = { if (it == WeiboRecordMode.PUSH) modePushLabel else modeReadLabel },
            onSelect = viewModel::setWeiboRecordMode
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_threads_record_mode),
            selected = threadsRecordMode,
            options = listOf(ThreadsRecordMode.PUSH, ThreadsRecordMode.READ),
            label = { if (it == ThreadsRecordMode.PUSH) modePushLabel else modeReadLabel },
            onSelect = viewModel::setThreadsRecordMode
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_reddit_record_mode),
            selected = redditRecordMode,
            options = listOf(RedditRecordMode.PUSH, RedditRecordMode.READ),
            label = { if (it == RedditRecordMode.PUSH) modePushLabel else modeReadLabel },
            onSelect = viewModel::setRedditRecordMode
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_bluesky_record_mode),
            selected = blueskyRecordMode,
            options = listOf(BlueskyRecordMode.PUSH, BlueskyRecordMode.READ),
            label = { if (it == BlueskyRecordMode.PUSH) modePushLabel else modeReadLabel },
            onSelect = viewModel::setBlueskyRecordMode
        )
    }
}
