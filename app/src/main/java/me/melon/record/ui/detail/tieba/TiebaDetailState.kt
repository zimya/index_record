package me.melon.record.ui.detail.tieba

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember


import me.melon.record.data.model.UniversalRecord
import me.melon.record.viewmodel.TiebaDetailData
import me.melon.record.viewmodel.TiebaMetadata
import me.melon.record.viewmodel.observeTiebaDetail

@Composable
internal fun rememberTiebaDetailData(record: UniversalRecord): TiebaDetailData {
    val flow = remember(record.platformId) { observeTiebaDetail(record) }
    val data by flow.collectAsState(initial = TiebaDetailData(record))
    return data
}

internal fun tiebaFloorLabel(record: UniversalRecord): String {
    if (record.recordType == "lou") return "1 楼 · 首楼"
    val floor = TiebaMetadata(record).number("floor")?.takeIf { it > 0 }
    return floor?.let { "$it 楼" } ?: "楼号未记录"
}

internal fun tiebaReplyAuthorLabel(reply: UniversalRecord, owner: UniversalRecord?): String =
    if (owner != null && reply.authorId.isNotBlank() && reply.authorId == owner.authorId) "楼中楼 · 层主"
    else "楼中楼回复"
