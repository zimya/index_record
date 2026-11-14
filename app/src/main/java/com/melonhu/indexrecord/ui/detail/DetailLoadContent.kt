package com.melonhu.indexrecord.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.viewmodel.DetailLoadState

/** Keep feedback outside scrolling content, so errors stay actionable without shifting item keys. */
@Composable
internal fun DetailLoadContent(
    loadState: DetailLoadState,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        if (loadState == DetailLoadState.Loading) {
            // Do not mount a short seed list and clamp the retained reading position.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("正在读取本地记录…")
                }
            }
        } else {
            if (loadState is DetailLoadState.Failed) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            loadState.message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isRetrying) CircularProgressIndicator(Modifier.size(20.dp))
                        TextButton(onClick = onRetry, enabled = !isRetrying) {
                            Text(if (isRetrying) "正在重试…" else "重试")
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

internal fun localRecordedCountLabel(count: Int): String = "本地已记录 $count 条"
