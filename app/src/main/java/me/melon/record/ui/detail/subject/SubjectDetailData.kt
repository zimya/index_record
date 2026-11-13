package me.melon.record.ui.detail.subject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.data.model.UniversalRecord
import me.melon.record.viewmodel.DoubanSubjectData
import me.melon.record.viewmodel.DoubanSubjectViewModel

@Composable
internal fun rememberSubjectDetailData(record: UniversalRecord): DoubanSubjectData {
    val viewModel: DoubanSubjectViewModel = viewModel(
        key = "douban-subject:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DoubanSubjectViewModel(record) as T
        }
    )
    val data by viewModel.state.collectAsState()
    return data
}
