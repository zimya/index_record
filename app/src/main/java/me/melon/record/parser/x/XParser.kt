package me.melon.record.parser.x

import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.RawDataParser

/**
 * X (Twitter) 平台总解析器。
 * 负责解析首页推荐流、推文详情带评论、搜索结果流、书签列表、喜欢列表等所有 GraphQL 响应。
 */
object XParser : RawDataParser {

    override val platform: String = "twitter"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("twitter.com") && !cleanUrl.contains("x.com") && raw.hostApp != "com.twitter.android") {
            return false
        }
        return cleanUrl.contains("/graphql/")
                || cleanUrl.contains("/HomeTimeline")
                || cleanUrl.contains("/ConversationTimeline")
                || cleanUrl.contains("/SearchTimeline")
                || cleanUrl.contains("/Bookmarks")
                || cleanUrl.contains("/Likes")
                || cleanUrl.contains("/UserTweets")
                || cleanUrl.contains("/HomeLatestTimeline")
                || raw.json.contains("\"tweet_results\"")
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        return XTimelineParser.parse(raw)
    }
}
