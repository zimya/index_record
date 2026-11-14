package com.melonhu.indexrecord.ui.home

import com.melonhu.indexrecord.presentation.HomeRecordGroupKind
import com.melonhu.indexrecord.presentation.homeRecordGroupPresentation
import com.melonhu.indexrecord.data.model.UniversalRecord

internal sealed interface HomeListItem {
    val key: String
}

internal data class RecordListItem(val record: UniversalRecord) : HomeListItem {
    override val key: String = "record_${record.id}"
}

internal data class AnswerGroupListItem(
    val questionKey: String,
    val questionTitle: String,
    val answers: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "answer_group_${questionKey}_$firstId"
}

internal data class InterestGroupListItem(
    val subjectKey: String,
    val subjectTitle: String,
    val typeLabel: String,
    val interests: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "interest_group_${subjectKey}_$firstId"
}

internal fun groupRecordsForHome(records: List<UniversalRecord>): List<HomeListItem> {
    val result = ArrayList<HomeListItem>(records.size)
    val groups = records.map(::homeRecordGroupPresentation)
    var i = 0
    while (i < records.size) {
        val group = groups[i]
        var end = i + 1
        if (group != null) {
            while (end < records.size && group.sameGroupAs(groups[end])) end++
        }
        if (group != null && end - i >= 2) {
            val members = records.subList(i, end).toList()
            val firstId = members.first().id
            result.add(when (group.kind) {
                HomeRecordGroupKind.ANSWERS -> AnswerGroupListItem(
                    group.target.contentId, group.title, members, firstId
                )
                HomeRecordGroupKind.INTERESTS -> InterestGroupListItem(
                    group.target.contentId, group.title, group.typeLabel, members, firstId
                )
            })
        } else {
            result.add(RecordListItem(records[i]))
        }
        i = end
    }
    return result
}
