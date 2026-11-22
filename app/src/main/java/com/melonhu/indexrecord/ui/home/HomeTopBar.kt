package com.melonhu.indexrecord.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.delay
import com.melonhu.indexrecord.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(
    isSearchActive: Boolean,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    filter: HomeViewModel.HomeFilter,
    onOpenFilter: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    AnimatedContent(
        targetState = isSearchActive,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                .togetherWith(fadeOut(animationSpec = tween(90)))
        },
        label = "TopBarTransition"
    ) { active ->
        if (active) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
                delay(100)
                runCatching { focusRequester.requestFocus() }
                keyboardController?.show()
            }

            TopAppBar(
                title = {
                    TextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            onTextFieldValueChange(newValue)
                            if (newValue.text != searchQuery) {
                                onSearchQueryChange(newValue.text)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboardController?.hide()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (textFieldValue.text.isNotEmpty()) {
                                IconButton(onClick = {
                                    onTextFieldValueChange(TextFieldValue("", TextRange.Zero))
                                    onSearchQueryChange("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear))
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        keyboardController?.hide()
                        onSearchActiveChange(false)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onSearchActiveChange(true) }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_placeholder))
                    }
                    IconButton(onClick = onOpenFilter) {
                        val hasFilter = filter.isActive
                        BadgedBox(
                            badge = {
                                if (hasFilter) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (hasFilter) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                                contentDescription = stringResource(R.string.filter_title),
                                tint = if (hasFilter) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    }
}
