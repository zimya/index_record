package me.melon.record.ui.components

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.melon.record.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    filter: HomeViewModel.HomeFilter,
    availableApps: List<String>,
    availablePlatforms: List<String>,
    availableTypes: List<String>,
    onDismiss: () -> Unit,
    onApply: (startMillis: Long?, endMillis: Long?, apps: Set<String>, platforms: Set<String>, types: Set<String>) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var startMillis by remember { mutableStateOf(filter.startMillis) }
    var endMillis by remember { mutableStateOf(filter.endMillis) }
    var selectedApps by remember { mutableStateOf(filter.apps) }
    var selectedPlatforms by remember { mutableStateOf(filter.platforms) }
    var selectedTypes by remember { mutableStateOf(filter.types) }

    var pickingStart by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }

    val startText = remember(startMillis) { formatDateTime(startMillis) }
    val endText = remember(endMillis) { formatDateTime(endMillis) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("过滤器", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        pickingStart = true
                        showDatePicker = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("开始：$startText") }
                OutlinedButton(
                    onClick = {
                        pickingStart = false
                        showDatePicker = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("结束：$endText") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("平台", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
            if (availablePlatforms.isEmpty()) {
                Text("暂无可筛选的平台", color = MaterialTheme.colorScheme.outline)
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (p in availablePlatforms) {
                        val selected = p in selectedPlatforms
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedPlatforms = if (selected) selectedPlatforms - p else selectedPlatforms + p
                            },
                            label = { Text(platformLabel(p)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("类型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
            if (availableTypes.isEmpty()) {
                Text("暂无可筛选的类型", color = MaterialTheme.colorScheme.outline)
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (t in availableTypes) {
                        val selected = t in selectedTypes
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedTypes = if (selected) selectedTypes - t else selectedTypes + t
                            },
                            label = { Text(typeLabel(t)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("宿主 App", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
            if (availableApps.isEmpty()) {
                Text("暂无可筛选的 App（需要新记录写入后才会出现）", color = MaterialTheme.colorScheme.outline)
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (pkg in availableApps) {
                        val selected = pkg in selectedApps
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedApps = if (selected) selectedApps - pkg else selectedApps + pkg
                            },
                            label = { Text(appLabel(pkg)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("清空") }
                Button(
                    onClick = { onApply(startMillis, endMillis, selectedApps, selectedPlatforms, selectedTypes) },
                    modifier = Modifier.weight(1f)
                ) { Text("应用") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        val initial = (if (pickingStart) startMillis else endMillis)
            ?: System.currentTimeMillis()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dateMillis = dateState.selectedDateMillis
                        showDatePicker = false
                        if (dateMillis == null) return@TextButton
                        val localDate = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
                        val existing = if (pickingStart) startMillis else endMillis
                        val existingTime =
                            existing?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(0, 0)

                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                val ldt = LocalDateTime.of(localDate, LocalTime.of(hour, minute))
                                val millis = ldt.atZone(zone).toInstant().toEpochMilli()
                                if (pickingStart) startMillis = millis else endMillis = millis
                            },
                            existingTime.hour,
                            existingTime.minute,
                            true
                        ).show()
                    }
                ) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }
}

private fun formatDateTime(millis: Long?): String {
    if (millis == null) return "不限"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun appLabel(pkg: String): String {
    return when (pkg) {
        "com.zhihu.android" -> "知乎"
        "com.douban.frodo" -> "豆瓣"
        "com.baidu.tieba" -> "贴吧"
        "com.xingin.xhs" -> "小红书"
        "com.twitter.android" -> "Twitter(X)"
        else -> pkg
    }
}

private fun platformLabel(platform: String): String {
    return when (platform) {
        "zhihu" -> "知乎"
        "douban" -> "豆瓣"
        "tieba" -> "贴吧"
        "xiaohongshu" -> "小红书"
        "twitter" -> "Twitter(X)"
        else -> platform
    }
}

private fun typeLabel(type: String): String {
    return when (type) {
        "question" -> "问题"
        "answer" -> "回答"
        "article" -> "文章"
        "comment" -> "评论"
        else -> type
    }
}
