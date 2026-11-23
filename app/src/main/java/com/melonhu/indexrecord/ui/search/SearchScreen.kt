package com.melonhu.indexrecord.ui.search

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.search.EmbeddingModelManager
import com.melonhu.indexrecord.search.HybridSearchEngine
import com.melonhu.indexrecord.search.ModelDownloadStatus
import com.melonhu.indexrecord.search.SearchFilter
import com.melonhu.indexrecord.search.SearchIndexingState
import com.melonhu.indexrecord.search.SearchMatchSource
import com.melonhu.indexrecord.ui.components.ImageViewerDialog
import com.melonhu.indexrecord.ui.components.filterAppLabel
import com.melonhu.indexrecord.ui.detail.DetailOverlayHost
import com.melonhu.indexrecord.viewmodel.DetailNavigationViewModel
import com.melonhu.indexrecord.viewmodel.SearchUiState
import com.melonhu.indexrecord.viewmodel.SearchViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(viewModel: SearchViewModel = viewModel()) {
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val availableApps by viewModel.availableAppsFlow.collectAsState(initial = emptyList())
    val availableTypes by viewModel.availableTypesFlow.collectAsState(initial = emptyList())
    val indexingState by viewModel.indexingState.collectAsState()
    val uiState by viewModel.searchFlow.collectAsState(initial = SearchUiState())
    val navigation: DetailNavigationViewModel = viewModel()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var showProgressSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.onSearchTabOpened()
    }

    DetailOverlayHost(navigation = navigation) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_search)) },
                    actions = {
                        IndexingProgressEntryButton(
                            state = indexingState,
                            onClick = { showProgressSheet = true }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = if (query.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_clear)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        viewModel.submitSearch()
                    })
                )

                SearchFilterBar(
                    filter = filter,
                    availableApps = availableApps,
                    availableTypes = availableTypes,
                    onPickStartTime = {
                        keyboardController?.hide()
                        pickingStart = true
                        showDatePicker = true
                    },
                    onPickEndTime = {
                        keyboardController?.hide()
                        pickingStart = false
                        showDatePicker = true
                    },
                    onToggleApp = viewModel::toggleApp,
                    onToggleType = viewModel::toggleType,
                    onToggleSource = viewModel::toggleSource,
                    onClearMetadataFilter = viewModel::clearMetadataFilter
                )

                AnimatedVisibility(visible = uiState.isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                when {
                    uiState.searchError != null -> {
                        val isMissingModelError =
                            uiState.searchError == HybridSearchEngine.MODEL_NOT_DOWNLOADED_ERROR
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = if (isMissingModelError) {
                                        stringResource(R.string.search_model_required_error)
                                    } else {
                                        uiState.searchError.orEmpty()
                                    },
                                    color = if (isMissingModelError) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (isMissingModelError) {
                                    FilledTonalButton(onClick = { showProgressSheet = true }) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.search_model_download_btn))
                                    }
                                }
                            }
                        }
                    }

                    query.isBlank() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.search_idle_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    !uiState.isSearching && uiState.results.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.home_empty_search),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (filter.hasMetadataFilter) {
                                    Text(
                                        text = stringResource(R.string.filter_empty_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(onClick = viewModel::clearMetadataFilter) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.filter_clear))
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)
                        ) {
                            items(
                                items = uiState.results,
                                key = { it.record.id }
                            ) { item ->
                                SearchResultCard(
                                    item = item,
                                    highlightTerms = uiState.highlightTerms,
                                    onClick = {
                                        keyboardController?.hide()
                                        navigation.push(item.record)
                                    },
                                    onImageClick = { urls, index ->
                                        keyboardController?.hide()
                                        viewerUrls = urls
                                        viewerIndex = index
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val zone = remember { ZoneId.systemDefault() }
        val initialLocalDate = (if (pickingStart) filter.startMillis else filter.endMillis)
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: LocalDate.now(zone)
        val initialUtcMillis = remember(initialLocalDate) {
            initialLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dateMillis = dateState.selectedDateMillis
                        showDatePicker = false
                        if (dateMillis != null) {
                            val localDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.of("UTC")).toLocalDate()
                            if (pickingStart) {
                                val millis = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
                                viewModel.setStartTime(millis)
                            } else {
                                val millis = localDate.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
                                viewModel.setEndTime(millis)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.filter_apply))
                }
            },
            dismissButton = {
                Row {
                    val isSet = if (pickingStart) filter.startMillis != null else filter.endMillis != null
                    if (isSet) {
                        TextButton(
                            onClick = {
                                if (pickingStart) {
                                    viewModel.setStartTime(null)
                                } else {
                                    viewModel.setEndTime(null)
                                }
                                showDatePicker = false
                            }
                        ) {
                            Text(stringResource(R.string.filter_clear))
                        }
                    }
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showProgressSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showProgressSheet = false },
            sheetState = sheetState
        ) {
            IndexingProgressSheetContent(
                state = indexingState,
                onPause = viewModel::pauseIndexing,
                onResume = viewModel::resumeIndexing,
                onRetry = viewModel::retryFailedIndexing,
                onClearImageCache = viewModel::clearImageCache,
                onStartModelDownload = viewModel::startModelDownload,
                onPauseModelDownload = viewModel::pauseModelDownload,
                onCancelModelDownload = viewModel::cancelModelDownload,
                onDeleteModel = viewModel::deleteDownloadedModel
            )
        }
    }

    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            onDismiss = { viewerUrls = emptyList() }
        )
    }
}

@Composable
private fun SearchFilterBar(
    filter: SearchFilter,
    availableApps: List<String>,
    availableTypes: List<String>,
    onPickStartTime: () -> Unit,
    onPickEndTime: () -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleType: (String) -> Unit,
    onToggleSource: (SearchMatchSource) -> Unit,
    onClearMetadataFilter: () -> Unit
) {
    var showAppMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }

    val appLabel = remember(filter.apps) {
        when (filter.apps.size) {
            0 -> null
            1 -> filterAppLabel(filter.apps.first())
            else -> "${filterAppLabel(filter.apps.first())} +${filter.apps.size - 1}"
        }
    } ?: stringResource(R.string.search_filter_platform)

    val typeLabel = remember(filter.types) {
        when (filter.types.size) {
            0 -> null
            1 -> RecordContentPolicy.filterTypeLabel(filter.types.first())
            else -> "${RecordContentPolicy.filterTypeLabel(filter.types.first())} +${filter.types.size - 1}"
        }
    } ?: stringResource(R.string.filter_type)

    val allSourcesSelected = filter.sources.size == SearchMatchSource.entries.size
    val sourceLabel = if (allSourcesSelected) {
        stringResource(R.string.search_filter_source)
    } else {
        stringResource(
            R.string.search_filter_source_count,
            filter.sources.size,
            SearchMatchSource.entries.size
        )
    }

    val visibleTypes = remember(availableTypes, filter.apps) {
        if (filter.apps.isEmpty()) {
            availableTypes
        } else {
            val selectedPlatforms = filter.apps.mapNotNull { PlatformCatalog.platformForPackage(it) }.toSet()
            availableTypes.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in selectedPlatforms }
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (filter.hasMetadataFilter) {
            item(key = "clear_filters") {
                FilterChip(
                    selected = true,
                    onClick = onClearMetadataFilter,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.filter_clear),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.filter_clear)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }
        }

        item(key = "start_time") {
            val startTimeText = remember(filter.startMillis) {
                formatSingleTimeChipLabel(filter.startMillis)
            }
            val startLabel = if (startTimeText != null) {
                stringResource(R.string.filter_time_start, startTimeText)
            } else {
                stringResource(R.string.filter_start_time)
            }
            FilterChip(
                selected = filter.startMillis != null,
                onClick = onPickStartTime,
                label = { Text(startLabel, maxLines = 1) }
            )
        }

        item(key = "end_time") {
            val endTimeText = remember(filter.endMillis) {
                formatSingleTimeChipLabel(filter.endMillis)
            }
            val endLabel = if (endTimeText != null) {
                stringResource(R.string.filter_time_end, endTimeText)
            } else {
                stringResource(R.string.filter_end_time)
            }
            FilterChip(
                selected = filter.endMillis != null,
                onClick = onPickEndTime,
                label = { Text(endLabel, maxLines = 1) }
            )
        }

        item(key = "platform") {
            Box {
                FilterChip(
                    selected = filter.apps.isNotEmpty(),
                    onClick = { showAppMenu = true },
                    label = { Text(appLabel, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                DropdownMenu(
                    expanded = showAppMenu,
                    onDismissRequest = { showAppMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    if (availableApps.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_no_data)) },
                            enabled = false,
                            onClick = {}
                        )
                    } else {
                        availableApps.forEach { pkg ->
                            val checked = pkg in filter.apps
                            DropdownMenuItem(
                                text = { Text(filterAppLabel(pkg)) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = { onToggleApp(pkg) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "type") {
            Box {
                FilterChip(
                    selected = filter.types.isNotEmpty(),
                    onClick = { showTypeMenu = true },
                    label = { Text(typeLabel, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                DropdownMenu(
                    expanded = showTypeMenu,
                    onDismissRequest = { showTypeMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    if (visibleTypes.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_no_data)) },
                            enabled = false,
                            onClick = {}
                        )
                    } else {
                        visibleTypes.forEach { typeKey ->
                            val checked = typeKey in filter.types
                            DropdownMenuItem(
                                text = { Text(RecordContentPolicy.filterTypeLabel(typeKey)) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = { onToggleType(typeKey) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "source") {
            Box {
                FilterChip(
                    selected = !allSourcesSelected,
                    onClick = { showSourceMenu = true },
                    label = { Text(sourceLabel, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                DropdownMenu(
                    expanded = showSourceMenu,
                    onDismissRequest = { showSourceMenu = false }
                ) {
                    val sourceOptions = listOf(
                        SearchMatchSource.TEXT_EXACT to R.string.search_source_text_exact,
                        SearchMatchSource.OCR_EXACT to R.string.search_source_ocr_exact,
                        SearchMatchSource.TEXT_SEMANTIC to R.string.search_source_text_semantic,
                        SearchMatchSource.IMAGE_VISUAL to R.string.search_source_image_visual
                    )
                    sourceOptions.forEach { (source, labelRes) ->
                        val checked = source in filter.sources
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            leadingIcon = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null
                                )
                            },
                            onClick = { onToggleSource(source) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatSingleTimeChipLabel(millis: Long?): String? {
    if (millis == null) return null
    val cal = Calendar.getInstance()
    val currentYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = millis
    val year = cal.get(Calendar.YEAR)
    val pattern = if (year != currentYear) "yyyy/MM/dd" else "MM/dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

@Composable
private fun IndexingProgressEntryButton(
    state: SearchIndexingState,
    onClick: () -> Unit
) {
    val overallPercent = state.overallProgressPercent.roundToInt().coerceIn(0, 100)
    val isActiveOrPending = state.isRunning || state.isPaused || state.pendingTotalCount > 0

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (state.isPaused) {
            MaterialTheme.colorScheme.secondaryContainer
        } else if (isActiveOrPending) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isActiveOrPending) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.isModelInitializing ||
                        state.modelDownloadState.status == ModelDownloadStatus.VERIFYING
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { (state.overallProgressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                Text(
                    text = if (state.isPaused && !state.isModelDownloading) {
                        stringResource(R.string.search_progress_paused_short, overallPercent)
                    } else {
                        "$overallPercent%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.isPaused && !state.isModelDownloading) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.search_progress_title),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (state.isModelReady) {
                        stringResource(R.string.search_progress_ready_short)
                    } else {
                        stringResource(R.string.search_progress_model_needed_short)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IndexingProgressSheetContent(
    state: SearchIndexingState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onClearImageCache: () -> Unit,
    onStartModelDownload: () -> Unit,
    onPauseModelDownload: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onDeleteModel: () -> Unit
) {
    val context = LocalContext.current
    val cacheSizeText = Formatter.formatShortFileSize(context, state.imageCacheSizeBytes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.search_progress_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.isModelInitializing) {
                    Text(
                        text = stringResource(R.string.search_index_stage_loading_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (state.isPaused) {
                FilledTonalButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.search_index_resume))
                }
            } else if (state.isRunning || state.pendingTotalCount > 0) {
                FilledTonalButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.search_index_pause))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ModelDownloadCard(
            state = state,
            onStartDownload = onStartModelDownload,
            onPauseDownload = onPauseModelDownload,
            onCancelDownload = onCancelModelDownload,
            onDeleteModel = onDeleteModel
        )

        Spacer(modifier = Modifier.height(16.dp))

        PipelineProgressRow(
            title = stringResource(R.string.search_pipeline_text_semantic),
            statusSubtitle = when {
                !state.isModelReady -> stringResource(R.string.search_pipeline_need_model)
                state.isPaused && state.pendingTextCount > 0 -> stringResource(R.string.search_pipeline_paused)
                state.isModelInitializing && state.pendingTextCount > 0 -> stringResource(R.string.search_index_stage_loading_model)
                state.isTextRunning -> stringResource(R.string.search_pipeline_running_gpu)
                state.pendingTextCount == 0 -> stringResource(R.string.search_pipeline_done)
                else -> stringResource(R.string.search_pipeline_queued)
            },
            done = state.doneTextCount + state.failedTextCount,
            total = state.totalTextCount,
            pending = state.pendingTextCount,
            failed = state.failedTextCount,
            progress = state.textProgress
        )

        Spacer(modifier = Modifier.height(14.dp))

        PipelineProgressRow(
            title = stringResource(R.string.search_pipeline_image_download),
            statusSubtitle = buildString {
                append(
                    when {
                        state.isPaused && (state.pendingDownloadCount > 0 || state.pendingOcrCount > 0) ->
                            stringResource(R.string.search_pipeline_paused)
                        state.isDownloadRunning -> stringResource(R.string.search_pipeline_downloading_ocr)
                        state.pendingDownloadCount == 0 -> stringResource(R.string.search_pipeline_done)
                        else -> stringResource(R.string.search_pipeline_queued)
                    }
                )
                if (state.totalDownloadCount > 0) {
                    append(" · ")
                    append(stringResource(R.string.search_pipeline_ocr_count, state.doneOcrCount, state.totalDownloadCount))
                }
            },
            done = state.doneDownloadCount + state.failedDownloadCount,
            total = state.totalDownloadCount,
            pending = state.pendingDownloadCount,
            failed = state.failedDownloadCount,
            progress = state.downloadProgress
        )

        Spacer(modifier = Modifier.height(14.dp))

        PipelineProgressRow(
            title = stringResource(R.string.search_pipeline_image_semantic),
            statusSubtitle = when {
                !state.isModelReady -> stringResource(R.string.search_pipeline_need_model)
                state.isPaused && state.pendingImageVecCount > 0 -> stringResource(R.string.search_pipeline_paused)
                state.isImageVecRunning -> stringResource(R.string.search_pipeline_running_gpu)
                state.isWaitingForTextBeforeImageVec -> stringResource(R.string.search_pipeline_waiting_text)
                state.pendingImageVecCount == 0 -> stringResource(R.string.search_pipeline_done)
                else -> stringResource(R.string.search_pipeline_waiting_download)
            },
            done = state.doneImageVecCount + state.failedImageVecCount,
            total = state.totalImageVecCount,
            pending = state.pendingImageVecCount,
            failed = state.failedImageVecCount,
            progress = state.imageVecProgress
        )

        state.lastError?.let { err ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalFailed = state.failedTextCount + state.failedDownloadCount + state.failedImageVecCount
            OutlinedButton(
                onClick = onRetry,
                enabled = totalFailed > 0 || !state.isRunning,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (totalFailed > 0) {
                        stringResource(R.string.search_index_retry_count, totalFailed)
                    } else {
                        stringResource(R.string.search_index_recheck)
                    },
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onClearImageCache,
                enabled = state.imageCacheSizeBytes > 0L,
                colors = ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.weight(1.3f)
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.search_clear_image_cache, cacheSizeText),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ModelDownloadCard(
    state: SearchIndexingState,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit
) {
    val context = LocalContext.current
    val dl = state.modelDownloadState
    val downloadedText = Formatter.formatShortFileSize(context, dl.downloadedBytes)
    val totalText = Formatter.formatShortFileSize(context, dl.totalBytes)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_model_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                when (dl.status) {
                    ModelDownloadStatus.READY -> {
                        TextButton(onClick = onDeleteModel) {
                            Text(
                                text = stringResource(R.string.search_model_delete_btn),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    ModelDownloadStatus.NOT_DOWNLOADED -> {
                        FilledTonalButton(onClick = onStartDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.search_model_download_btn))
                        }
                    }

                    ModelDownloadStatus.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onCancelDownload) {
                                Text(stringResource(R.string.search_model_cancel_btn))
                            }
                            FilledTonalButton(onClick = onStartDownload) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.search_model_resume_btn))
                            }
                        }
                    }

                    ModelDownloadStatus.FAILED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (dl.downloadedBytes > 0L) {
                                TextButton(onClick = onCancelDownload) {
                                    Text(stringResource(R.string.search_model_cancel_btn))
                                }
                            }
                            FilledTonalButton(onClick = onStartDownload) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.search_model_retry_btn))
                            }
                        }
                    }

                    ModelDownloadStatus.DOWNLOADING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onCancelDownload) {
                                Text(stringResource(R.string.search_model_cancel_btn))
                            }
                            OutlinedButton(onClick = onPauseDownload) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.search_model_pause_btn))
                            }
                        }
                    }

                    ModelDownloadStatus.VERIFYING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            val subtitle = when (dl.status) {
                ModelDownloadStatus.NOT_DOWNLOADED -> stringResource(R.string.search_model_not_downloaded, totalText)
                ModelDownloadStatus.DOWNLOADING -> stringResource(R.string.search_model_downloading, downloadedText, totalText)
                ModelDownloadStatus.PAUSED -> stringResource(R.string.search_model_paused, downloadedText, totalText)
                ModelDownloadStatus.VERIFYING -> stringResource(R.string.search_model_verifying)
                ModelDownloadStatus.READY -> stringResource(R.string.search_model_ready, totalText)
                ModelDownloadStatus.FAILED -> dl.errorMessage ?: stringResource(R.string.search_model_not_downloaded, totalText)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (dl.status == ModelDownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (dl.status == ModelDownloadStatus.DOWNLOADING ||
                dl.status == ModelDownloadStatus.PAUSED ||
                dl.status == ModelDownloadStatus.VERIFYING
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                if (dl.status == ModelDownloadStatus.VERIFYING) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { dl.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineProgressRow(
    title: String,
    statusSubtitle: String,
    done: Int,
    total: Int,
    pending: Int,
    failed: Int,
    progress: Float
) {
    val percent = (progress * 100f).roundToInt().coerceIn(0, 100)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$percent% ($done/$total)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (failed > 0) {
                    stringResource(R.string.search_pipeline_pending_failed, pending, failed)
                } else {
                    stringResource(R.string.search_pipeline_pending_only, pending)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
