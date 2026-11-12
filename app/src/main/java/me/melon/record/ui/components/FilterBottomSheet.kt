package me.melon.record.ui.components

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Icon
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import me.melon.record.ui.theme.PlatformThemeColor
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
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
    availableTypes: List<String>,
    onDismiss: () -> Unit,
    onApply: (startMillis: Long?, endMillis: Long?, apps: Set<String>, types: Set<String>) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    val zone = remember { ZoneId.systemDefault() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    var startMillis by remember { mutableStateOf(filter.startMillis) }
    var endMillis by remember { mutableStateOf(filter.endMillis) }
    var selectedApps by remember { mutableStateOf(filter.apps) }
    var selectedTypes by remember { mutableStateOf(filter.types) }

    var pickingStart by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }

    val startText = remember(startMillis) { formatDateTime(startMillis) }
    val endText = remember(endMillis) { formatDateTime(endMillis) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Text("过滤器", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        for (p in availableTypes) {
                            val selected = p in selectedTypes
                            FilterChipWithCheck(
                                selected = selected,
                                onClick = {
                                    selectedTypes = if (selected) selectedTypes - p else selectedTypes + p
                                },
                                label = typeLabel(p)
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
                        for (p in availableApps) {
                            val selected = p in selectedApps
                            FilterChipWithCheck(
                                selected = selected,
                                onClick = {
                                    selectedApps = if (selected) selectedApps - p else selectedApps + p
                                },
                                label = appLabel(p),
                                themeColor = getPlatformThemeColor(p, isDark)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) { Text("清空") }

                Button(
                    onClick = { onApply(startMillis, endMillis, selectedApps, selectedTypes) },
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

@Composable
private fun FilterChipWithCheck(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    themeColor: PlatformThemeColor? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = themeColor?.container ?: MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = themeColor?.text ?: MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = themeColor?.text ?: MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = if (selected && themeColor != null) {
            BorderStroke(1.dp, themeColor.text.copy(alpha = 0.5f))
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = selected)
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else {
            null
        }
    )
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
        "com.twitter.android" -> "𝕏"
        "com.instagram.barcelona", "threads" -> "Threads"
        "com.sina.weibo" -> "微博"
        "com.weico.international" -> "微博国际版"
        else -> pkg
    }
}

private fun typeLabel(type: String): String {
    return when (type) {
        "status" -> "微博"
        "question" -> "问题"
        "answer" -> "回答"
        "article" -> "文章"
        "pin" -> "想法"
        "comment" -> "评论"
        "topic" -> "帖子"
        "thread" -> "主帖"
        "floor" -> "楼层"
        "review" -> "长评"
        "interest" -> "短评"
        "subject" -> "条目"
        else -> type
    }
}
