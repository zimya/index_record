package com.melonhu.indexrecord.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private val CardCornerRadius = 12.dp
private val ShadowBlurRadius = 4.dp
private val ShadowOffsetY = 2.dp

@Composable
internal fun HomeRecordCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val shadow = remember(density) { createCardShadow(density) }

    Card(
        modifier = modifier
            .fixedCardShadow(shadow)
            .clip(RoundedCornerShape(CardCornerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(CardCornerRadius),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = content
    )
}

private data class CardShadow(
    val bitmap: Bitmap,
    val padding: Float,
    val cornerRadius: Float,
    val offsetY: Float,
    val slice: Int
)

private fun createCardShadow(density: Density): CardShadow = with(density) {
    val cornerRadius = CardCornerRadius.toPx()
    val blurRadius = ShadowBlurRadius.toPx()
    val padding = ceil(blurRadius * 3f).toInt()
    val slice = ceil(padding + cornerRadius).toInt()
    val bitmapSize = slice * 2 + 1
    val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(31, 0, 0, 0)
        maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
    }
    // Blur on a software canvas: hardware canvas support for mask filters varies by Android version.
    Canvas(bitmap).drawRoundRect(
        padding.toFloat(), padding.toFloat(),
        (bitmapSize - padding).toFloat(), (bitmapSize - padding).toFloat(),
        cornerRadius, cornerRadius, paint
    )
    CardShadow(bitmap, padding.toFloat(), cornerRadius, ShadowOffsetY.toPx(), slice)
}

private fun Modifier.fixedCardShadow(shadow: CardShadow): Modifier = drawWithCache {
    val sourceEdges = intArrayOf(0, shadow.slice, shadow.slice + 1, shadow.bitmap.width)
    // Nine-slice scaling preserves the corner blur and edge thickness even for very tall cards.
    val cornerX = shadow.cornerRadius.coerceAtMost(size.width / 2f)
    val cornerY = shadow.cornerRadius.coerceAtMost(size.height / 2f)
    val xEdges = floatArrayOf(-shadow.padding, cornerX, size.width - cornerX, size.width + shadow.padding)
    val yEdges = floatArrayOf(-shadow.padding, cornerY, size.height - cornerY, size.height + shadow.padding)
    val source = Rect()
    val destination = RectF()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    onDrawBehind {
        drawIntoCanvas { canvas ->
            for (row in 0..2) {
                for (column in 0..2) {
                    source.set(sourceEdges[column], sourceEdges[row], sourceEdges[column + 1], sourceEdges[row + 1])
                    destination.set(
                        xEdges[column], yEdges[row] + shadow.offsetY,
                        xEdges[column + 1], yEdges[row + 1] + shadow.offsetY
                    )
                    canvas.nativeCanvas.drawBitmap(shadow.bitmap, source, destination, paint)
                }
            }
        }
    }
}
