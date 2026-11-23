package com.melonhu.indexrecord.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.melonhu.indexrecord.ui.theme.PlatformThemeColor
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import com.melonhu.indexrecord.ui.theme.isAppInDarkTheme
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.viewmodel.HomeViewModel
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
    FilterBottomSheet(
        initialStartMillis = filter.startMillis,
        initialEndMillis = filter.endMillis,
        initialApps = filter.apps,
        initialTypes = filter.types,
        availableApps = availableApps,
        availableTypes = availableTypes,
        onDismiss = onDismiss,
        onApply = onApply,
        onClear = onClear
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    initialStartMillis: Long?,
    initialEndMillis: Long?,
    initialApps: Set<String>,
    initialTypes: Set<String>,
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

    var startMillis by remember { mutableStateOf(initialStartMillis) }
    var endMillis by remember { mutableStateOf(initialEndMillis) }
    var selectedApps by remember { mutableStateOf(initialApps) }
    var selectedTypes by remember {
        val initialPlatforms = initialApps.mapNotNull { PlatformCatalog.platformForPackage(it) }.toSet()
        mutableStateOf(
            if (initialPlatforms.isEmpty()) emptySet()
            else initialTypes.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in initialPlatforms }.toSet()
        )
    }

    val visibleTypes = remember(availableTypes, selectedApps) {
        filterTypesForApps(availableTypes, selectedApps)
    }

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
            .fillMaxHeight(0.85f)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.filter_time), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val unlimitedText = stringResource(R.string.filter_time_unlimited)
                    OutlinedButton(
                        onClick = {
                            pickingStart = true
                            showDatePicker = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.filter_time_start, startText ?: unlimitedText)) }

                    OutlinedButton(
                        onClick = {
                            pickingStart = false
                            showDatePicker = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.filter_time_end, endText ?: unlimitedText)) }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.filter_app), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                if (availableApps.isEmpty()) {
                    Text(stringResource(R.string.common_no_data), color = MaterialTheme.colorScheme.outline)
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
                                    val newApps = if (selected) selectedApps - p else selectedApps + p
                                    selectedApps = newApps
                                    val newPlatforms = newApps.mapNotNull { PlatformCatalog.platformForPackage(it) }.toSet()
                                    selectedTypes = selectedTypes.filter {
                                        RecordContentPolicy.platformFromFilterTypeKey(it) in newPlatforms
                                    }.toSet()
                                },
                                label = filterAppLabel(p),
                                themeColor = getPlatformThemeColor(p, isDark)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.filter_type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                if (selectedApps.isEmpty()) {
                    Text(stringResource(R.string.filter_select_app_first), color = MaterialTheme.colorScheme.outline)
                } else if (visibleTypes.isEmpty()) {
                    Text(stringResource(R.string.common_no_data), color = MaterialTheme.colorScheme.outline)
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (p in visibleTypes) {
                            val selected = p in selectedTypes
                            val platform = RecordContentPolicy.platformFromFilterTypeKey(p)
                            FilterChipWithCheck(
                                selected = selected,
                                onClick = {
                                    selectedTypes = if (selected) selectedTypes - p else selectedTypes + p
                                },
                                label = typeLabel(p),
                                themeColor = getPlatformThemeColor(platform, isDark)
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
                ) { Text(stringResource(R.string.filter_clear)) }

                Button(
                    onClick = { onApply(startMillis, endMillis, selectedApps, selectedTypes) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.filter_apply)) }
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
                ) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) }
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

private fun formatDateTime(millis: Long?): String? {
    if (millis == null) return null
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

internal fun filterAppLabel(pkg: String): String {
    return com.melonhu.indexrecord.data.PlatformCatalog.packageLabel(pkg)
        ?: com.melonhu.indexrecord.data.PlatformCatalog.knownDisplayName(pkg)
        ?: pkg
}

internal fun typeLabel(type: String): String {
    return com.melonhu.indexrecord.presentation.RecordContentPolicy.filterTypeLabel(type)
}

internal fun filterTypesForApps(
    types: List<String>,
    selectedApps: Set<String>
): List<String> {
    if (selectedApps.isEmpty()) return emptyList()
    val selectedPlatforms = selectedApps.mapNotNull { PlatformCatalog.platformForPackage(it) }.toSet()
    if (selectedPlatforms.isEmpty()) return emptyList()
    return types.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in selectedPlatforms }
}
