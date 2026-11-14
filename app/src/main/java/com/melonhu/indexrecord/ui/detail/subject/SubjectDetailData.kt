package com.melonhu.indexrecord.ui.detail.subject

import androidx.compose.runtime.Composable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.data.model.UniversalRecord

import com.melonhu.indexrecord.viewmodel.DoubanSubjectViewModel

@Composable
internal fun rememberSubjectDetailViewModel(record: UniversalRecord): DoubanSubjectViewModel {
    val viewModel: DoubanSubjectViewModel = viewModel(
        key = "douban-subject:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DoubanSubjectViewModel(record) as T
        }
    )
    return viewModel
}
