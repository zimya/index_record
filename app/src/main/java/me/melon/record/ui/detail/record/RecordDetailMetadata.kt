package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.engagementCount
import me.melon.record.ui.components.viewEngagementCount

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordDetailMetadata(record: UniversalRecord) {
    val metadata = remember(record.extraMetadata) {
        try {
            JsonParser.parseString(record.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    if (metadata.size() > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (record.platform in setOf("twitter", "weibo", "threads")) {
                val likeCount = metadata.engagementCount("like_count", "likeCount", "voteup_count")
                val rtCount = metadata.engagementCount("retweet_count", "repost_count", "repostCount")
                val repCount = metadata.engagementCount("reply_count", "comment_count", "commentCount")
                val bmCount = metadata.engagementCount("bookmark_count", "bookmarkCount")
                val viewsCount = metadata.viewEngagementCount(record.platform)

                if (likeCount > 0L) {
                    MetadataBadge(Icons.Outlined.ThumbUp, me.melon.record.parser.x.XUtils.formatCount(likeCount))
                }
                if (rtCount > 0L) {
                    MetadataBadge(Icons.Outlined.Repeat, me.melon.record.parser.x.XUtils.formatCount(rtCount))
                }
                if (repCount > 0L) {
                    MetadataBadge(Icons.Outlined.ChatBubbleOutline, me.melon.record.parser.x.XUtils.formatCount(repCount))
                }
                if (record.platform == "threads") {
                    val quoteCount = metadata.engagementCount("quote_count")
                    val shareCount = metadata.engagementCount("reshare_count")
                    if (quoteCount > 0L) {
                        MetadataBadge(Icons.Outlined.FormatQuote, "引用 ${me.melon.record.parser.x.XUtils.formatCount(quoteCount)}")
                    }
                    if (shareCount > 0L) {
                        MetadataBadge(Icons.Outlined.Share, "分享 ${me.melon.record.parser.x.XUtils.formatCount(shareCount)}")
                    }
                }
                if (bmCount > 0L) {
                    MetadataBadge(Icons.Outlined.BookmarkBorder, me.melon.record.parser.x.XUtils.formatCount(bmCount))
                }
                if (viewsCount > 0L) {
                    MetadataBadge(Icons.Outlined.RemoveRedEye, me.melon.record.parser.x.XUtils.formatCount(viewsCount))
                }
            } else {
                metadata.get("voteup_count")?.let {
                    MetadataBadge(Icons.Outlined.ThumbUp, it.asString)
                }
                metadata.get("comment_count")?.let {
                    MetadataBadge(Icons.Outlined.ChatBubbleOutline, it.asString)
                }
                metadata.get("ip_info")?.let {
                    MetadataBadge(Icons.Outlined.Place, it.asString)
                }
                metadata.get("favlists_count")?.let {
                    MetadataBadge(Icons.Outlined.StarOutline, it.asString)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}
