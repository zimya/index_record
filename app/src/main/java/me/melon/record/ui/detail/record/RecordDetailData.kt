package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.data.model.UniversalRecord
import me.melon.record.viewmodel.RecordDetailUiState
import me.melon.record.viewmodel.RecordDetailViewModel

@Composable
internal fun rememberRecordDetailData(record: UniversalRecord): RecordDetailUiState {
    val viewModel: RecordDetailViewModel = viewModel(
        key = "detail:${record.platform}:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordDetailViewModel(record) as T
        }
    )
    val state by viewModel.state.collectAsState()
    return state
}
