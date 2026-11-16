package com.melonhu.indexrecord.ui.stats

import androidx.compose.ui.graphics.Color
import com.melonhu.indexrecord.ui.theme.getPlatformBrandColor

fun colorFromPackage(pkg: String): Color {
    return getPlatformBrandColor(pkg) ?: run {
        val h = (pkg.hashCode().toLong().ushr(1) % 360L).toFloat()
        Color.hsv(h, 0.65f, 0.90f)
    }
}

fun appLabel(pkg: String): String {
    return com.melonhu.indexrecord.data.PlatformCatalog.packageLabel(pkg)
        ?: com.melonhu.indexrecord.data.PlatformCatalog.knownDisplayName(pkg)
        ?: if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
}
