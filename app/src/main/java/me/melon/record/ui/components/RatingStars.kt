package me.melon.record.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StarHalf
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RatingStars(
    stars: Double,
    modifier: Modifier = Modifier,
    starSize: Dp = 14.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val full = stars.toInt().coerceIn(0, 5)
        val hasHalf = (stars - full) >= 0.25
        val empty = (5 - full - if (hasHalf) 1 else 0).coerceAtLeast(0)

        for (i in 0 until full) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = Color(0xFFFFA000),
                modifier = Modifier.size(starSize)
            )
        }
        if (hasHalf) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.StarHalf,
                contentDescription = null,
                tint = Color(0xFFFFA000),
                modifier = Modifier.size(starSize)
            )
        }
        for (i in 0 until empty) {
            Icon(
                imageVector = Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = Color(0xFFBDBDBD),
                modifier = Modifier.size(starSize)
            )
        }
    }
}
