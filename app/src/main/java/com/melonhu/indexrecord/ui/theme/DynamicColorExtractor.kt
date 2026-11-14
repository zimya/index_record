package com.melonhu.indexrecord.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.pow

data class DynamicColors(
    val dark: Color,
    val light: Color
)

/**
 * 从同一个主题色生成深浅背景。
 *
 * 图片提取是同步操作，应放在后台线程执行。
 * 输出背景始终不透明。
 */
object DynamicColorExtractor {

    // 深色值越大越亮；浅色值越小，背景色越明显。
    private const val DARK_LIGHTNESS = 0.24f
    private const val LIGHT_LIGHTNESS = 0.84f

    private const val DARK_MAX_SATURATION = 0.65f
    private const val LIGHT_MAX_SATURATION = 0.50f
    private const val SATURATION_BOOST = 1.15f
    private const val NEUTRAL_THRESHOLD = 0.08f

    private const val MIN_CONTRAST = 4.5

    private val DARK_TEXT = 0xFF202020.toInt()
    private val LIGHT_TEXT = 0xFFFFFFFF.toInt()

    private val fallback = DynamicColors(
        dark = Color(0xFF303030),
        light = Color(0xFFD6D6D6)
    )

    fun extractRepresentativeColors(bitmap: Bitmap): DynamicColors {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return fallback
        }

        val palette = Palette.from(bitmap)
            .maximumColorCount(24)
            .clearFilters()
            .generate()

        val swatches = palette.swatches
        val maxPopulation = swatches.maxOfOrNull { it.population }
            ?: return fallback

        // 优先选择有一定面积的彩色区域，
        // 避免大面积黑白背景掩盖图片的主题色。
        val candidates = swatches.filter { swatch ->
            val hsl = swatch.hsl

            hsl[1] >= 0.12f &&
                hsl[2] in 0.10f..0.90f &&
                swatch.population >= maxPopulation * 0.05f
        }

        // 综合考虑面积和饱和度。
        // 对面积进行幂次压缩，给较小的彩色区域一些权重。
        val seed = candidates.maxByOrNull { swatch ->
            val populationWeight =
                (swatch.population.toDouble() / maxPopulation).pow(0.65)

            val saturationWeight = 0.35 + 0.65 * swatch.hsl[1]

            populationWeight * saturationWeight
        } ?: palette.dominantSwatch
            ?: swatches.maxByOrNull { it.population }
            ?: return fallback

        return createColors(seed.rgb)
    }

    /**
     * 支持：
     * RRGGBB / #RRGGBB
     * AARRGGBB / #AARRGGBB
     *
     * 八位格式按 Android 的 AARRGGBB 解析；
     * 忽略 alpha，生成不透明背景。
     */
    fun parseHexToRepresentativeColors(hex: String): DynamicColors? {
        val clean = hex.trim().removePrefix("#")

        if (clean.length != 6 && clean.length != 8) {
            return null
        }

        if (!clean.all {
                it in '0'..'9' ||
                    it in 'a'..'f' ||
                    it in 'A'..'F'
            }
        ) {
            return null
        }

        val value = clean.toLongOrNull(16) ?: return null

        val opaqueRgb =
            (value and 0x00FFFFFFL).toInt() or 0xFF000000.toInt()

        return createColors(opaqueRgb)
    }

    private fun createColors(rgb: Int): DynamicColors {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)

        val hue = hsl[0]
        val saturation = hsl[1]

        // 接近灰色时不强制提高饱和度，避免无端染色。
        val darkSaturation = if (saturation < NEUTRAL_THRESHOLD) {
            saturation
        } else {
            (saturation * SATURATION_BOOST)
                .coerceIn(0.25f, DARK_MAX_SATURATION)
        }

        val lightSaturation = if (saturation < NEUTRAL_THRESHOLD) {
            saturation
        } else {
            (saturation * SATURATION_BOOST)
                .coerceIn(0.20f, LIGHT_MAX_SATURATION)
        }

        return DynamicColors(
            dark = Color(
                createBackground(
                    hue = hue,
                    saturation = darkSaturation,
                    lightness = DARK_LIGHTNESS,
                    isDark = true
                )
            ),
            light = Color(
                createBackground(
                    hue = hue,
                    saturation = lightSaturation,
                    lightness = LIGHT_LIGHTNESS,
                    isDark = false
                )
            )
        )
    }

    /**
     * 检查实际 RGB 对比度，必要时微调明度。
     *
     * 深色背景按不透明白字检查；
     * 浅色背景按不透明 #202020 文字检查。
     * 其他文字颜色、透明文字或叠加层需要单独检查。
     */
    private fun createBackground(
        hue: Float,
        saturation: Float,
        lightness: Float,
        isDark: Boolean
    ): Int {
        val hsl = floatArrayOf(hue, saturation, lightness)
        val foreground = if (isDark) LIGHT_TEXT else DARK_TEXT

        var background = ColorUtils.HSLToColor(hsl)

        repeat(100) {
            val contrast = ColorUtils.calculateContrast(
                foreground,
                background
            )

            if (contrast >= MIN_CONTRAST) {
                return background
            }

            val adjustment = if (isDark) -0.01f else 0.01f
            hsl[2] = (hsl[2] + adjustment).coerceIn(0f, 1f)

            background = ColorUtils.HSLToColor(hsl)
        }

        return background
    }
}
