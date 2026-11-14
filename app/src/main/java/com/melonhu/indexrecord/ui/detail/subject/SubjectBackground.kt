package com.melonhu.indexrecord.ui.detail.subject

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.melonhu.indexrecord.ui.theme.DynamicColorExtractor
import com.melonhu.indexrecord.ui.theme.DynamicColors
import com.melonhu.indexrecord.ui.theme.isAppInDarkTheme

@Composable
internal fun rememberSubjectBackgroundColor(coverUrl: String, headerBgColorHex: String): Color {
    val context = LocalContext.current
    val initialColors = remember(headerBgColorHex) {
        DynamicColorExtractor.parseHexToRepresentativeColors(headerBgColorHex)
    }
    var dynamicColors by remember(coverUrl) { mutableStateOf<DynamicColors?>(null) }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val imageLoader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false)
                    .crossfade(false)
                    .setHeader("Referer", "https://frodo.douban.com/")
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap ?: result?.toBitmap()
                if (bitmap != null) {
                    val colors = DynamicColorExtractor.extractRepresentativeColors(bitmap)
                    withContext(Dispatchers.Main) {
                        dynamicColors = colors
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val isDark = isAppInDarkTheme()
    val targetBgColor = remember(dynamicColors, initialColors, isDark) {
        val colors = dynamicColors ?: initialColors
        if (colors != null) {
            if (isDark) colors.dark else colors.light
        } else {
            if (isDark) Color(0xFF141414) else Color(0xFFF7F7F7)
        }
    }

    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 350),
        label = "SubjectBgColor"
    )
    return animatedBgColor
}
