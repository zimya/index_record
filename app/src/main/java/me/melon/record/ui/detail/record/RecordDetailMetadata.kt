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
            if (record.platform == "twitter" || record.platform == "weibo") {
                val likeCount = if (metadata.has("like_count") && !metadata.get("like_count").isJsonNull) {
                    metadata.get("like_count").asLong
                } else if (metadata.has("voteup_count") && !metadata.get("voteup_count").isJsonNull) {
                    metadata.get("voteup_count").asLong
                } else 0L

                val rtCount = if (metadata.has("retweet_count") && !metadata.get("retweet_count").isJsonNull) {
                    metadata.get("retweet_count").asLong
                } else 0L

                val repCount = if (metadata.has("reply_count") && !metadata.get("reply_count").isJsonNull) {
                    metadata.get("reply_count").asLong
                } else if (metadata.has("comment_count") && !metadata.get("comment_count").isJsonNull) {
                    metadata.get("comment_count").asLong
                } else 0L

                val bmCount = if (metadata.has("bookmark_count") && !metadata.get("bookmark_count").isJsonNull) {
                    metadata.get("bookmark_count").asLong
                } else 0L

                val viewsCount = if (metadata.has("views_count") && !metadata.get("views_count").isJsonNull) {
                    metadata.get("views_count").asLong
                } else 0L

                if (likeCount > 0L) {
                    MetadataBadge(Icons.Outlined.ThumbUp, me.melon.record.parser.x.XUtils.formatCount(likeCount))
                }
                if (rtCount > 0L) {
                    MetadataBadge(Icons.Outlined.Repeat, me.melon.record.parser.x.XUtils.formatCount(rtCount))
                }
                if (repCount > 0L) {
                    MetadataBadge(Icons.Outlined.ChatBubbleOutline, me.melon.record.parser.x.XUtils.formatCount(repCount))
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
