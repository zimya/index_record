package com.melonhu.indexrecord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AvatarImage(url: String, size: Dp, modifier: Modifier = Modifier) {
    if (url.isBlank()) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }

    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun CoverImage(url: String, modifier: Modifier = Modifier) {
    if (url.isBlank()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }

    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}
