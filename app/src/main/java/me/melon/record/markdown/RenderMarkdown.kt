@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package me.melon.record.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import io.ratex.RaTeXEngine
import io.ratex.RaTeXView
import kotlinx.coroutines.launch

@Composable
fun RenderMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    selectable: Boolean = true,
    enableScroll: Boolean = true,
    onImageClick: ((List<String>, Int) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val document = remember(markdown) {
        if (markdown.contains("<p>") || markdown.contains("</div>") || markdown.contains("<figure")) {
            htmlToMdAst(markdown)
        } else {
            markdownToMdAst(markdown)
        }
    }
    RenderMarkdownDocument(
        document = document,
        modifier = modifier,
        scrollState = scrollState,
        selectable = selectable,
        enableScroll = enableScroll,
        onImageClick = onImageClick,
        onLinkClick = onLinkClick,
        header = header,
        footer = footer,
    )
}

@Composable
fun RenderMarkdownDocument(
    document: MdDocument,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    selectable: Boolean = true,
    enableScroll: Boolean = true,
    onImageClick: ((List<String>, Int) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val previewImageUrls = remember(document) { document.collectImageUrls() }
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val footnoteDefRequesters = remember { mutableMapOf<String, BringIntoViewRequester>() }
    val footnoteRefRequesters = remember { mutableMapOf<String, BringIntoViewRequester>() }

    val resolvedOnLinkClick: (String) -> Unit = onLinkClick ?: { url ->
        runCatching { uriHandler.openUri(url) }
        Unit
    }

    val onFootnoteClick: (String) -> Unit = { label ->
        coroutineScope.launch {
            footnoteDefRequesters[label]?.bringIntoView()
        }
    }

    val content: @Composable () -> Unit = {
        Column(
            modifier = modifier
                .then(if (enableScroll) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            header?.invoke()

            for (block in document.children) {
                RenderBlockNode(
                    node = block,
                    imageUrls = previewImageUrls,
                    onImageClick = onImageClick,
                    onLinkClick = resolvedOnLinkClick,
                    onFootnoteClick = onFootnoteClick,
                    footnoteRefRequesters = footnoteRefRequesters
                )
            }

            val footnotes = remember(document) { document.allFootnotes() }
            if (footnotes.isNotEmpty()) {
                RenderFootnotesSection(
                    footnotes = footnotes,
                    footnoteDefRequesters = footnoteDefRequesters,
                    footnoteRefRequesters = footnoteRefRequesters,
                    onLinkClick = resolvedOnLinkClick,
                    coroutineScope = coroutineScope
                )
            }

            footer?.invoke()
        }
    }

    if (selectable) {
        SelectionContainer { content() }
    } else {
        content()
    }
}

@Composable
private fun RenderBlockNode(
    node: MdNode,
    imageUrls: List<String>,
    onImageClick: ((List<String>, Int) -> Unit)?,
    onLinkClick: (String) -> Unit,
    onFootnoteClick: (String) -> Unit,
    footnoteRefRequesters: MutableMap<String, BringIntoViewRequester>,
) {
    when (node) {
        is MdHeading -> RenderHeading(node, onLinkClick)
        is MdParagraph -> {
            if (node.children.any { it is MdImage }) {
                val chunks = mutableListOf<List<MdNode>>()
                var cur = mutableListOf<MdNode>()
                for (c in node.children) {
                    if (c is MdImage) {
                        if (cur.isNotEmpty()) {
                            chunks.add(cur)
                            cur = mutableListOf()
                        }
                        chunks.add(listOf(c))
                    } else {
                        cur.add(c)
                    }
                }
                if (cur.isNotEmpty()) chunks.add(cur)

                for (chunk in chunks) {
                    if (chunk.size == 1 && chunk[0] is MdImage) {
                        val img = chunk[0] as MdImage
                        RenderImage(
                            url = img.url,
                            altText = img.altText,
                            width = img.width,
                            height = img.height,
                            imageUrls = imageUrls,
                            onImageClick = onImageClick
                        )
                    } else {
                        val p = MdParagraph().apply { appendChildren(chunk) }
                        RenderParagraph(p, onLinkClick, onFootnoteClick, footnoteRefRequesters)
                    }
                }
            } else {
                RenderParagraph(node, onLinkClick, onFootnoteClick, footnoteRefRequesters)
            }
        }
        is MdFencedCodeBlock -> RenderCodeBlock(node)
        is MdMathBlock -> RenderMathBlock(node.formula)
        is MdBlockQuote -> RenderBlockQuote(node, imageUrls, onImageClick, onLinkClick, onFootnoteClick, footnoteRefRequesters)
        is MdList -> RenderList(node, imageUrls, onImageClick, onLinkClick, onFootnoteClick, footnoteRefRequesters)
        is MdTable -> RenderTable(node, onLinkClick)
        is MdThematicBreak -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        is MdImage -> RenderImage(
            url = node.url,
            altText = node.altText,
            width = node.width,
            height = node.height,
            imageUrls = imageUrls,
            onImageClick = onImageClick
        )
        else -> {
            for (child in node.children) {
                RenderBlockNode(child, imageUrls, onImageClick, onLinkClick, onFootnoteClick, footnoteRefRequesters)
            }
        }
    }
}

@Composable
private fun RenderHeading(heading: MdHeading, onLinkClick: (String) -> Unit) {
    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        4 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(heading, linkColor) {
        buildAnnotatedString {
            buildInlineSpans(heading.children, this, linkColor)
        }
    }
    Text(
        text = annotated,
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun RenderParagraph(
    paragraph: MdParagraph,
    onLinkClick: (String) -> Unit,
    onFootnoteClick: (String) -> Unit,
    footnoteRefRequesters: MutableMap<String, BringIntoViewRequester>,
) {
    val density = LocalDensity.current
    val contentColor = MaterialTheme.colorScheme.onBackground
    val linkColor = MaterialTheme.colorScheme.primary
    val colorArgb = contentColor.toArgb()

    val inlineMap = remember(paragraph, contentColor) {
        mutableMapOf<String, InlineTextContent>()
    }
    val annotated = remember(paragraph, contentColor, linkColor) {
        inlineMap.clear()
        buildAnnotatedString {
            buildInlineSpansWithMath(
                nodes = paragraph.children,
                builder = this,
                linkColor = linkColor,
                density = density,
                colorArgb = colorArgb,
                inlineMap = inlineMap
            )
        }
    }

    val footnoteLabels = remember(paragraph) {
        val labels = mutableListOf<String>()
        fun findLabels(n: MdNode) {
            if (n is MdFootnoteReference) labels.add(n.label)
            for (c in n.children) findLabels(c)
        }
        findLabels(paragraph)
        labels
    }
    val refRequester = remember(paragraph) {
        if (footnoteLabels.isNotEmpty()) BringIntoViewRequester() else null
    }
    if (refRequester != null) {
        for (lbl in footnoteLabels) {
            footnoteRefRequesters[lbl] = refRequester
        }
    }

    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasClickables = remember(annotated) {
        annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.length).isNotEmpty() ||
        annotated.getStringAnnotations(tag = "FOOTNOTE_REF", start = 0, end = annotated.length).isNotEmpty()
    }

    val gestureModifier = if (hasClickables) {
        Modifier.pointerInput(annotated, onLinkClick, onFootnoteClick) {
            detectTapGestures { pos ->
                layoutResult.value?.let { layout ->
                    val offset = layout.getOffsetForPosition(pos)
                    annotated.getStringAnnotations(tag = "FOOTNOTE_REF", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            onFootnoteClick(annotation.item)
                            return@detectTapGestures
                        }
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            onLinkClick(annotation.item)
                            return@detectTapGestures
                        }
                }
            }
        }
    } else Modifier

    val bringModifier = if (refRequester != null) Modifier.bringIntoViewRequester(refRequester) else Modifier

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = contentColor,
            lineHeight = 26.sp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(bringModifier)
            .then(gestureModifier),
        inlineContent = inlineMap,
        onTextLayout = { layoutResult.value = it }
    )
}

private fun buildInlineSpansWithMath(
    nodes: List<MdNode>,
    builder: AnnotatedString.Builder,
    linkColor: Color,
    density: androidx.compose.ui.unit.Density,
    colorArgb: Int,
    inlineMap: MutableMap<String, InlineTextContent>,
) {
    var mathCounter = 0
    fun walk(node: MdNode) {
        when (node) {
            is MdText -> builder.append(node.text)
            is MdInlineMath -> {
                val mathId = "math_${mathCounter++}"
                var wSp = 40.sp
                var hSp = 22.sp

                try {
                    val displayList = RaTeXEngine.parseBlocking(node.formula, false, colorArgb)
                    val fontSizePx = 16f * density.density
                    val calculatedWidthSp = with(density) { (displayList.width * fontSizePx).toFloat().toSp() }
                    val calculatedHeightSp = with(density) { ((displayList.height + displayList.depth) * fontSizePx).toFloat().toSp() }
                    if (calculatedWidthSp.value > 0f) wSp = calculatedWidthSp
                    if (calculatedHeightSp.value > 0f) hSp = maxOf(calculatedHeightSp.value, 18f).sp
                } catch (_: Exception) {
                }

                inlineMap[mathId] = InlineTextContent(
                    Placeholder(
                        width = wSp,
                        height = hSp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    AndroidView(
                        factory = { ctx ->
                            RaTeXView(ctx).apply {
                                this.latex = node.formula
                                this.fontSize = 16f
                                this.displayMode = false
                                this.color = colorArgb
                            }
                        },
                        update = { view ->
                            view.latex = node.formula
                            view.fontSize = 16f
                            view.displayMode = false
                            view.color = colorArgb
                        }
                    )
                }
                builder.appendInlineContent(mathId, "[公式]")
            }
            is MdInlineCode -> {
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        background = Color.Gray.copy(alpha = 0.15f)
                    )
                ) {
                    builder.append(" ${node.literal} ")
                }
            }
            is MdEmphasis -> {
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    for (c in node.children) walk(c)
                }
            }
            is MdStrongEmphasis -> {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    for (c in node.children) walk(c)
                }
            }
            is MdStrikethrough -> {
                builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    for (c in node.children) walk(c)
                }
            }
            is MdLink -> {
                val start = builder.length
                builder.pushStringAnnotation(tag = "URL", annotation = node.destination)
                builder.withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    for (c in node.children) walk(c)
                }
                builder.pop()
            }
            is MdFootnoteReference -> {
                val displayIndex = if (node.index > 0) node.index.toString() else node.label
                val displayText = "[$displayIndex]"
                builder.pushStringAnnotation(tag = "FOOTNOTE_REF", annotation = node.label)
                builder.withStyle(
                    SpanStyle(
                        color = linkColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        baselineShift = BaselineShift.Superscript
                    )
                ) {
                    builder.append(displayText)
                }
                builder.pop()
            }
            is MdHardLineBreak -> builder.append("\n")
            else -> {
                for (c in node.children) walk(c)
            }
        }
    }

    for (node in nodes) {
        walk(node)
    }
}

private fun buildInlineSpans(
    nodes: List<MdNode>,
    builder: AnnotatedString.Builder,
    linkColor: Color,
) {
    for (node in nodes) {
        when (node) {
            is MdText -> builder.append(node.text)
            is MdEmphasis -> {
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    buildInlineSpans(node.children, builder, linkColor)
                }
            }
            is MdStrongEmphasis -> {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    buildInlineSpans(node.children, builder, linkColor)
                }
            }
            is MdInlineCode -> {
                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    builder.append(node.literal)
                }
            }
            is MdLink -> {
                builder.pushStringAnnotation(tag = "URL", annotation = node.destination)
                builder.withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    buildInlineSpans(node.children, builder, linkColor)
                }
                builder.pop()
            }
            else -> buildInlineSpans(node.children, builder, linkColor)
        }
    }
}

@Composable
fun RenderMathBlock(latex: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onBackground.toArgb()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            AndroidView(
                factory = { ctx ->
                    RaTeXView(ctx).apply {
                        this.latex = latex
                        this.fontSize = 20f
                        this.displayMode = true
                        this.color = color
                    }
                },
                update = { view ->
                    view.latex = latex
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = color
                }
            )
        }
    }
}

@Composable
fun RenderCodeBlock(block: MdFencedCodeBlock, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val highlightedText = remember(block.code, block.language, isDark) {
        val lang = SyntaxLanguage.getByName(block.language) ?: SyntaxLanguage.DEFAULT
        val theme = SyntaxThemes.atom(darkMode = isDark)
        val highlights = Highlights.Builder()
            .code(block.code)
            .language(lang)
            .theme(theme)
            .build()

        buildAnnotatedString {
            append(block.code)
            highlights.getHighlights().forEach { highlight ->
                val start = highlight.location.start.coerceIn(0, block.code.length)
                val end = highlight.location.end.coerceIn(0, block.code.length)
                if (start < end) {
                    when (highlight) {
                        is ColorHighlight -> addStyle(
                            SpanStyle(color = Color(0xFF000000.toInt() or highlight.rgb)),
                            start,
                            end
                        )
                        is BoldHighlight -> addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start,
                            end
                        )
                    }
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Code", block.code))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Code",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(
                    text = highlightedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RenderBlockQuote(
    quote: MdBlockQuote,
    imageUrls: List<String>,
    onImageClick: ((List<String>, Int) -> Unit)?,
    onLinkClick: (String) -> Unit,
    onFootnoteClick: (String) -> Unit,
    footnoteRefRequesters: MutableMap<String, BringIntoViewRequester>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (child in quote.children) {
                RenderBlockNode(child, imageUrls, onImageClick, onLinkClick, onFootnoteClick, footnoteRefRequesters)
            }
        }
    }
}

@Composable
private fun RenderList(
    list: MdList,
    imageUrls: List<String>,
    onImageClick: ((List<String>, Int) -> Unit)?,
    onLinkClick: (String) -> Unit,
    onFootnoteClick: (String) -> Unit,
    footnoteRefRequesters: MutableMap<String, BringIntoViewRequester>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        list.children.filterIsInstance<MdListItem>().forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                val bullet = if (list.isOrdered) "${list.startNumber + index}. " else "• "
                Text(
                    text = bullet,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (c in item.children) {
                        RenderBlockNode(c, imageUrls, onImageClick, onLinkClick, onFootnoteClick, footnoteRefRequesters)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderTable(table: MdTable, onLinkClick: (String) -> Unit) {
    val rows = table.children.filterIsInstance<MdTableRow>()
    if (rows.isEmpty()) return

    val maxCols = rows.maxOfOrNull { it.children.filterIsInstance<MdTableCell>().size } ?: 1
    if (maxCols == 0) return

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        val minColWidth = 64.dp
        val spacing = 12.dp
        val hPadding = 8.dp * 2
        val minTotalWidth = minColWidth * maxCols + spacing * (maxCols - 1) + hPadding
        val needScroll = minTotalWidth > maxWidth
        val tableWidth = if (needScroll) minTotalWidth else maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (needScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        ) {
            Column(modifier = Modifier.width(tableWidth)) {
                rows.forEachIndexed { rIdx, row ->
                    val isHeader = row.isHeader
                    val cells = row.children.filterIsInstance<MdTableCell>()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isHeader) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        for (cIdx in 0 until maxCols) {
                            if (cIdx < cells.size) {
                                val cell = cells[cIdx]
                                val align = when (cell.alignment) {
                                    MdTableAlignment.LEFT -> TextAlign.Start
                                    MdTableAlignment.CENTER -> TextAlign.Center
                                    MdTableAlignment.RIGHT -> TextAlign.End
                                    MdTableAlignment.NONE -> TextAlign.Start
                                }
                                val annotated = remember(cell) {
                                    buildAnnotatedString {
                                        buildInlineSpans(cell.children, this, Color.Blue)
                                    }
                                }
                                Text(
                                    text = annotated,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = align
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (rIdx < rows.lastIndex) {
                        HorizontalDivider(color = borderColor)
                    }
                }
            }
        }
    }
}

@Composable
fun RenderImage(
    url: String,
    altText: String,
    width: Int? = null,
    height: Int? = null,
    imageUrls: List<String> = listOf(url),
    onImageClick: ((List<String>, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val previewUrls = remember(imageUrls, url) {
        imageUrls.ifEmpty { listOf(url) }
    }
    val imageAspectRatio =
        if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height
        } else {
            null
        }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = altText,
            modifier = modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (imageAspectRatio != null) {
                        Modifier.aspectRatio(imageAspectRatio)
                    } else {
                        Modifier
                    }
                )
                .clickable {
                    val initialIndex = previewUrls.indexOf(url).takeIf { it >= 0 } ?: 0
                    onImageClick?.invoke(previewUrls, initialIndex)
                }
        )
    }
}

@Composable
private fun RenderFootnotesSection(
    footnotes: List<MdFootnoteDefinition>,
    footnoteDefRequesters: MutableMap<String, BringIntoViewRequester>,
    footnoteRefRequesters: Map<String, BringIntoViewRequester>,
    onLinkClick: (String) -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = "参考资料",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        for (footnote in footnotes) {
            val requester = remember(footnote.label) {
                footnoteDefRequesters.getOrPut(footnote.label) { BringIntoViewRequester() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "[${if (footnote.index > 0) footnote.index else footnote.label}]",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            footnoteRefRequesters[footnote.label]?.bringIntoView()
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (footnote.content.isNotBlank()) {
                        Text(
                            text = footnote.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (footnote.url.isNotBlank()) {
                        Text(
                            text = footnote.url,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline
                            ),
                            modifier = Modifier.clickable { onLinkClick(footnote.url) }
                        )
                    }
                }
            }
        }
    }
}
