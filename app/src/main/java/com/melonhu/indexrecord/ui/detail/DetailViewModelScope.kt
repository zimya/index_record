package com.melonhu.indexrecord.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** Lifetime belongs to the navigation entry, not to its temporarily visible composition. */
@Composable
internal fun DetailViewModelScope(owner: ViewModelStoreOwner, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
