package com.melonhu.indexrecord.ui.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.BuildConfig
import com.melonhu.indexrecord.R

private data class OpenSourceLibrary(
    val name: String,
    val license: String,
    val author: String,
    val url: String,
    val description: String
)

private val openSourceLibraries = listOf(
    OpenSourceLibrary(
        name = "Kotlin & Coroutines",
        license = "Apache-2.0",
        author = "JetBrains",
        url = "https://github.com/JetBrains/kotlin",
        description = "Modern programming language and asynchronous coroutine support"
    ),
    OpenSourceLibrary(
        name = "AndroidX & Jetpack Compose",
        license = "Apache-2.0",
        author = "Google",
        url = "https://developer.android.com/jetpack/compose",
        description = "Modern declarative UI toolkit and Android architecture components"
    ),
    OpenSourceLibrary(
        name = "ObjectBox",
        license = "Apache-2.0 / ObjectBox License",
        author = "ObjectBox Ltd.",
        url = "https://objectbox.io",
        description = "High-performance embedded object database for Android"
    ),
    OpenSourceLibrary(
        name = "LibXposed",
        license = "Apache-2.0",
        author = "LibXposed",
        url = "https://github.com/libxposed/api",
        description = "Next-generation modern Xposed framework API standard"
    ),
    OpenSourceLibrary(
        name = "Jsoup",
        license = "MIT",
        author = "Jonathan Hedley",
        url = "https://jsoup.org",
        description = "Java HTML parser that extracts and manipulates web data"
    ),
    OpenSourceLibrary(
        name = "Brotli",
        license = "MIT",
        author = "Google",
        url = "https://github.com/google/brotli",
        description = "Generic-purpose lossless compression algorithm decoder"
    ),
    OpenSourceLibrary(
        name = "CommonMark Java",
        license = "BSD-2-Clause",
        author = "Atlassian & Contributors",
        url = "https://github.com/commonmark/commonmark-java",
        description = "Java parser and renderer for Markdown text"
    ),
    OpenSourceLibrary(
        name = "Coil Compose",
        license = "Apache-2.0",
        author = "Coil Contributors",
        url = "https://github.com/coil-kt/coil",
        description = "Image loading library for Android backed by Kotlin Coroutines"
    ),
    OpenSourceLibrary(
        name = "Media3 ExoPlayer",
        license = "Apache-2.0",
        author = "Google",
        url = "https://github.com/androidx/media",
        description = "Application-level media player for Android"
    ),
    OpenSourceLibrary(
        name = "Gson",
        license = "Apache-2.0",
        author = "Google",
        url = "https://github.com/google/gson",
        description = "Java serialization and deserialization library for JSON"
    ),
    OpenSourceLibrary(
        name = "Ratex Android",
        license = "Apache-2.0",
        author = "erweixin",
        url = "https://github.com/erweixin/ratex-android",
        description = "Lightweight math rendering library based on KaTeX"
    ),
    OpenSourceLibrary(
        name = "Snipme Highlights",
        license = "Apache-2.0",
        author = "SnipMe",
        url = "https://github.com/SnipMe/highlights",
        description = "Syntax highlighting library for code snippets"
    ),
    OpenSourceLibrary(
        name = "AndroidX Palette",
        license = "Apache-2.0",
        author = "Google",
        url = "https://developer.android.com/jetpack/androidx/releases/palette",
        description = "Palette library to extract prominent colors from images"
    )
)

@Composable
internal fun AboutSettingsScreen(
    onBack: () -> Unit
) {
    var showLicensesDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val githubUrl = stringResource(R.string.about_github_url)
    val telegramUrl = stringResource(R.string.about_telegram_url)

    val openOrCopyUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            clipboardManager.setText(AnnotatedString(url))
            Toast.makeText(context, context.getString(R.string.about_link_copied), Toast.LENGTH_SHORT).show()
        }
    }

    val copyUrl: (String) -> Unit = { url ->
        clipboardManager.setText(AnnotatedString(url))
        Toast.makeText(context, context.getString(R.string.about_link_copied), Toast.LENGTH_SHORT).show()
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_about),
        onBack = onBack
    ) {
        // Hero Section: Index Illustration with ambient soft glow and bottom fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ambient soft theme glow behind Index
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Index Image: with smooth vertical fade to merge seamlessly into background
            Image(
                painter = painterResource(R.drawable.img_about_index),
                contentDescription = stringResource(R.string.about_index_image_desc),
                modifier = Modifier
                    .height(250.dp)
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Black,
                                0.88f to Color.Black,
                                1.0f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentScale = ContentScale.Fit
            )
        }

        // App Name & Version & Tagline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.about_app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = stringResource(
                        R.string.about_version_format,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Links & Open Source Licenses
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // GitHub Repository
                AboutLinkItem(
                    title = stringResource(R.string.about_github),
                    subtitle = githubUrl,
                    leadingPainter = painterResource(R.drawable.ic_github),
                    onClick = { openOrCopyUrl(githubUrl) },
                    onLongClick = { copyUrl(githubUrl) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                // Telegram Group
                AboutLinkItem(
                    title = stringResource(R.string.about_telegram),
                    subtitle = telegramUrl,
                    leadingPainter = painterResource(R.drawable.ic_telegram),
                    onClick = { openOrCopyUrl(telegramUrl) },
                    onLongClick = { copyUrl(telegramUrl) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                // Open Source Licenses List
                AboutLinkItem(
                    title = stringResource(R.string.about_licenses),
                    subtitle = stringResource(R.string.about_licenses_desc),
                    leadingVector = Icons.Outlined.Description,
                    onClick = { showLicensesDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(
            onDismiss = { showLicensesDialog = false },
            onOpenUrl = openOrCopyUrl
        )
    }
}

@Composable
private fun AboutLinkItem(
    title: String,
    subtitle: String,
    leadingPainter: Painter? = null,
    leadingVector: ImageVector? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val rowModifier = if (onLongClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingPainter != null) {
            Icon(
                painter = leadingPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        } else if (leadingVector != null) {
            Icon(
                imageVector = leadingVector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OpenSourceLicensesDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.about_licenses_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // App License
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.about_app_license_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "GPL-3.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.about_app_license_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.about_third_party_licenses_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                openSourceLibraries.forEach { lib ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenUrl(lib.url) }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = lib.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(end = 8.dp)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = lib.license,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = lib.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = lib.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
