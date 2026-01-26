package me.melon.record.data.model
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.relation.ToOne

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
data class Article(
    @Id var id: Long = 0,
    var title: String = "",
    var contentMarkdown: String = "", // 这里同时也存储 HTML 内容
    var author: String = "",
    var createTime: Long = System.currentTimeMillis(),
    var sourceUrl: String = "",
    // 存储扩展字段 JSON (ip_info, voteup_count 等)
    var extraData: String = ""
)
