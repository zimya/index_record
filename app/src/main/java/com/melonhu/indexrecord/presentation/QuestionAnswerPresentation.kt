package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.bodyPlainText

import com.melonhu.indexrecord.data.repository.ZhihuAnswerRef

internal data class QuestionAnswerPresentation(
    val author: String,
    val authorAvatar: String,
    val preview: String,
    val publishTime: Long,
    val statistics: List<RecordStatistic>
)

internal fun questionAnswerPresentation(
    ref: ZhihuAnswerRef,
    stored: DetailRecord?
): QuestionAnswerPresentation {
    if (stored != null) {
        val record = stored
        return QuestionAnswerPresentation(
            author = displayAuthor(record.authorName),
            authorAvatar = record.authorAvatar,
            preview = recordPreviewText(record),
            publishTime = record.publishTime,
            statistics = recordStatistics(record, StatisticsSurface.LIST)
        )
    }
    return QuestionAnswerPresentation(
        author = displayAuthor(ref.authorName),
        authorAvatar = ref.authorAvatar,
        preview = bodyPlainText(ref.excerpt, ref.excerptFormat),
        publishTime = (if (ref.updatedTimeSec > 0) ref.updatedTimeSec else ref.createdTimeSec) * 1000,
        statistics = buildList {
            if (ref.voteupCount >= 0) add(RecordStatistic(StatisticKind.LIKE, formatCount(ref.voteupCount), "赞同", ref.voteupCount))
            if (ref.commentCount >= 0) add(RecordStatistic(StatisticKind.REPLY, formatCount(ref.commentCount), "评论", ref.commentCount))
        }
    )
}
