package com.melonhu.indexrecord.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.ui.components.MainTopAppBar
import com.melonhu.indexrecord.viewmodel.HomeViewModel

@Composable
internal fun HomeTopBar(
    filter: HomeViewModel.HomeFilter,
    onOpenFilter: () -> Unit
) {
    MainTopAppBar(
        title = stringResource(R.string.app_name),
        actions = {
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
        }
    )
}
