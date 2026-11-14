package com.melonhu.indexrecord.ui.detail.tieba

import androidx.compose.runtime.Composable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel


import com.melonhu.indexrecord.data.model.UniversalRecord

import com.melonhu.indexrecord.data.model.TiebaMetadata
import com.melonhu.indexrecord.viewmodel.TiebaDetailViewModel

@Composable
internal fun rememberTiebaDetailViewModel(record: UniversalRecord): TiebaDetailViewModel {
    val viewModel: TiebaDetailViewModel = viewModel(
        key = "tieba:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TiebaDetailViewModel(record) as T
        }
    )
    return viewModel
}

internal fun tiebaFloorLabel(record: UniversalRecord): String {
    if (record.recordType == "lou") return "1 楼 · 首楼"
    val floor = TiebaMetadata(record.extraMetadata).floorNumber?.takeIf { it > 0 }
    return floor?.let { "$it 楼" } ?: "楼号未记录"
}

internal fun tiebaReplyAuthorLabel(reply: UniversalRecord, owner: UniversalRecord?): String =
    if (owner != null && reply.authorId.isNotBlank() && reply.authorId == owner.authorId) "楼中楼 · 层主"
    else "楼中楼回复"
