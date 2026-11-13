package me.melon.record.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditMarkdownTest {
    private fun nodes(root: MdNode): List<MdNode> = listOf(root) + root.children.flatMap(::nodes)

    @Test
    fun disablingMathKeepsDollarAmountsLiteralAtEveryNestingLevel() {
        val source = "Spend \$5 or \$10.\n\n> **Save \$2 or \$3.**\n\n| Price |\n| --- |\n| \$4 or \$8 |\n\n\$\$cash\$\$"
        val document = contentToMdAst(source, MarkdownContentFormat.MARKDOWN, enableMath = false)
        assertFalse(nodes(document).any { it is MdInlineMath || it is MdMathBlock })
        val text = nodes(document).filterIsInstance<MdText>().joinToString("") { it.text }
        assertTrue(text.contains("Spend \$5 or \$10."))
        assertTrue(text.contains("Save \$2 or \$3."))
        assertTrue(text.contains("\$4 or \$8"))
        assertTrue(text.contains("\$\$cash\$\$"))
    }

    @Test
    fun htmlLookingFencedAndInlineCodeNeverSelectTheHtmlRenderer() {
        val source = "```html\n<p>\$5</p>\n</div>\n<figure>example</figure>\n```\n\n`<p>\$10</p>`"
        val document = contentToMdAst(source, MarkdownContentFormat.MARKDOWN, enableMath = false)
        val code = document.children.filterIsInstance<MdFencedCodeBlock>().single()
        assertEquals("html", code.language)
        assertEquals("<p>\$5</p>\n</div>\n<figure>example</figure>\n", code.code)
        assertEquals("<p>\$10</p>", nodes(document).filterIsInstance<MdInlineCode>().single().literal)
    }

    @Test
    fun explicitMarkdownKeepsRawHtmlLiteralInsteadOfDroppingIt() {
        val document = contentToMdAst("<p>example</p>", MarkdownContentFormat.MARKDOWN, enableMath = false)
        assertTrue(nodes(document).filterIsInstance<MdText>().joinToString("") { it.text }.contains("<p>example</p>"))
    }

    @Test
    fun footnoteLookingCodeIsPreservedLiterally() {
        val document = contentToMdAst(
            "```text\nline [^1]: not a footnote\n```", MarkdownContentFormat.MARKDOWN, enableMath = false
        )
        assertEquals("line [^1]: not a footnote\n", document.children.filterIsInstance<MdFencedCodeBlock>().single().code)
    }

    @Test
    fun standardMarkdownUsesTheExistingRichAst() {
        val document = contentToMdAst(
            "**bold** ~~deleted~~ [link](https://example.com)\n\n> quote\n\n- first\n- second",
            MarkdownContentFormat.MARKDOWN, enableMath = false
        )
        val all = nodes(document)
        assertTrue(all.any { it is MdStrongEmphasis })
        assertTrue(all.any { it is MdStrikethrough })
        assertTrue(all.any { it is MdLink })
        assertTrue(all.any { it is MdBlockQuote })
        assertTrue(all.any { it is MdList })
    }

    @Test
    fun htmlAndMarkdownMathRequireExplicitSourceDeclarations() {
        val html = contentToMdAst("<p>example</p>", MarkdownContentFormat.HTML)
        assertEquals("example", nodes(html).filterIsInstance<MdText>().joinToString("") { it.text })
        val math = contentToMdAst("Inline \$x+1\$.\n\n\$\$x=2\$\$", MarkdownContentFormat.MARKDOWN, enableMath = true)
        assertTrue(nodes(math).any { it is MdInlineMath })
        assertTrue(nodes(math).any { it is MdMathBlock })
    }

    @Test
    fun plainTextNeverParsesMarkupOrMath() {
        val source = "<p>example</p> **bold** &amp; \$x\$\n[^1]: literal"
        val document = contentToMdAst(source, MarkdownContentFormat.PLAIN_TEXT, enableMath = true)
        assertEquals(source, nodes(document).filterIsInstance<MdText>().joinToString("\n") { it.text })
        assertFalse(nodes(document).any { it is MdStrongEmphasis || it is MdInlineMath || it is MdMathBlock })
        assertTrue(document.footnoteDefinitions.isEmpty())
    }

    @Test
    fun markdownMathIsDisabledUnlessExplicitlyEnabled() {
        val document = contentToMdAst("Inline \$x+1\$.\n\n\$\$x=2\$\$", MarkdownContentFormat.MARKDOWN)
        assertFalse(nodes(document).any { it is MdInlineMath || it is MdMathBlock })
        assertTrue(document.toPlainText().contains("\$x+1\$"))
    }

    @Test
    fun flattenedFootnoteLookingTextIsNotRepairedIntoDefinitions() {
        val document = contentToMdAst("before [^1]: not a definition", MarkdownContentFormat.MARKDOWN)
        assertTrue(document.footnoteDefinitions.isEmpty())
        assertEquals("before [^1]: not a definition", document.toPlainText())
    }
}
