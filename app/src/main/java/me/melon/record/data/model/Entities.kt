package me.melon.record.data.model

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

@Entity
data class RawData(
    @Id var id: Long = 0,
    var url: String = "",
    var json: String = "",
    var hostApp: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var isParsed: Boolean = false
)

@Entity
data class UniversalRecord(
    @Id var id: Long = 0,

    // 1. 来源标识 (Routing)
    @Index var platform: String = "",     // 平台： "zhihu", "douban", "twitter", "tieba"
    @Index var recordType: String = "",   // 类型： "article", "answer", "comment", "tweet", "thread"（Threads 主帖）, "lou"（贴吧主帖）
    @Index var sourceApp: String? = null,

    // 2. 唯一标识 (Deduplication) - 用于防止重复记录同一条内容
    @Unique var platformId: String = "",  // 该内容在原平台的唯一ID（例如知乎回答的ID）

    // 3. 核心内容 (Core Content)
    var title: String = "",               // 标题（推文、评论通常为空）
    var content: String = "",             // 正文；由 contentFormat 明确解释

    // 4. 作者信息 (Author)
    @Index var authorId: String = "",     // 作者在平台的ID
    var authorName: String = "",          // 作者昵称
    var authorAvatar: String = "",        // 作者头像URL

    // 5. 关系与溯源 (Relation)
    var sourceUrl: String = "",           // 抓取来源链接
    var parentId: String = "",            // 关系目标的存储 ID；领域含义统一由 RecordRelations 解释

    // 6. 时间戳 (Time)
    var publishTime: Long = 0,            // 内容在平台上的发布/更新时间
    var captureTime: Long = System.currentTimeMillis(), // 我们的抓取时间

    // 7. 扩展数据 (Extension) - JSON 格式
    var extraMetadata: String = "{}",

    // Stable wire value, not an enum ordinal. The default body is literal plain text.
    var contentFormat: String = RecordBodyFormat.PLAIN_TEXT.storageValue
)
