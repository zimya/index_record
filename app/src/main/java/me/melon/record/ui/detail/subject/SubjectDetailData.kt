package me.melon.record.ui.detail.subject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

internal class SubjectDetailData(
    val record: UniversalRecord,
    val relatedRecords: List<UniversalRecord>,
    val contentReady: Boolean
)

@Composable
internal fun rememberSubjectDetailData(record: UniversalRecord): SubjectDetailData {
    var currentRecord by remember(record) { mutableStateOf(record) }
    var relatedRecords by remember { mutableStateOf<List<UniversalRecord>>(emptyList()) }
    var contentReady by remember { mutableStateOf(false) }
    // 等列表项完整后再挂载列表，避免临时显示顶部以及钳制保存的滚动位置。

    // 监听与主动查询条目本体最新数据以及关联短评长评
    LaunchedEffect(record.platformId) {
        withContext(Dispatchers.IO) {
            if (ObjectBox.isInitialized) {
                val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

                fun refreshData() {
                    val realSubject = box.query()
                        .equal(UniversalRecord_.platformId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.recordType, "subject", QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                    if (realSubject != null) {
                        currentRecord = realSubject
                    }

                    val list = box.query()
                        .equal(UniversalRecord_.parentId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .orderDesc(UniversalRecord_.publishTime)
                        .build()
                        .find()
                    relatedRecords = list
                }

                refreshData()
                contentReady = true

                // 实时订阅更新，保证后台无论何时解析入库条目均可瞬时响应刷新
                val sub = box.query()
                    .equal(UniversalRecord_.platformId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .or()
                    .equal(UniversalRecord_.parentId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .subscribe()
                    .observer {
                        refreshData()
                    }

                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    sub.cancel()
                }
            }
        }
    }
    return SubjectDetailData(currentRecord, relatedRecords, contentReady)
}
