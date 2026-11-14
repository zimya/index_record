package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.runtime.Composable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.data.model.UniversalRecord

import com.melonhu.indexrecord.viewmodel.RecordDetailViewModel

@Composable
internal fun rememberRecordDetailViewModel(record: UniversalRecord): RecordDetailViewModel {
    val viewModel: RecordDetailViewModel = viewModel(
        key = "detail:${record.platform}:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordDetailViewModel(record) as T
        }
    )
    return viewModel
}
