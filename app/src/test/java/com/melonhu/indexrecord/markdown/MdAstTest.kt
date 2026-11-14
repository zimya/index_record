package com.melonhu.indexrecord.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdAstTest {

    @Test
    fun testParagraphAndTextConversion() {
        val html = "<p>这是一段普通文本，包含 <b>加粗</b> 和 <i>斜体</i> 以及 <a href=\"https://www.zhihu.com\">链接</a>。</p>"
        val markdown = zhihuHtmlToMarkdown(html)
        assertEquals("这是一段普通文本，包含 **加粗** 和 *斜体* 以及 [链接](https://www.zhihu.com)。", markdown)
    }

    @Test
    fun testHeadingsAndLists() {
        val html = """
            <h2>这是一个二级标题</h2>
            <ul>
              <li>列表项 1</li>
              <li>列表项 2</li>
            </ul>
        """.trimIndent()
        val markdown = zhihuHtmlToMarkdown(html)
        val expected = """
            ## 这是一个二级标题

            - 列表项 1
            - 列表项 2
        """.trimIndent()
        assertEquals(expected, markdown)
    }

    @Test
    fun testFormulas() {
        val html = """
            <p>行内公式：<img class="ztext-math" eeimg="0" src="https://www.zhihu.com/equation?tex=E%3Dmc%5E2" alt="E=mc^2"/></p>
            <p><img class="ztext-math" eeimg="2" src="https://www.zhihu.com/equation?tex=%5Csum_%7Bi%3D1%7D%5En%20i" alt="sum"/></p>
        """.trimIndent()
        val doc = htmlToMdAst(html)
        val markdown = doc.toMarkdown()

        assertTrue(markdown.contains("\$E=mc^2\$"))
        assertTrue(markdown.contains("$$\n\\sum_{i=1}^n i\n$$"))
    }

    @Test
    fun testImages() {
        val html = """
            <figure><img src="https://pic1.zhimg.com/test.jpg" data-caption="测试图片" data-rawwidth="800" data-rawheight="600"/></figure>
        """.trimIndent()
        val doc = htmlToMdAst(html)
        val markdown = doc.toMarkdown()

        assertEquals("![测试图片](https://pic1.zhimg.com/test.jpg)", markdown)
    }

    @Test
    fun testFootnoteHtmlConversion() {
        val html = """
            <p>文章内容<sup data-draft-type="reference" data-numero="1" data-text="参考论文" data-url="https://example.com/paper">[1]</sup>继续正文。</p>
        """.trimIndent()
        val doc = htmlToMdAst(html)
        assertEquals(1, doc.footnoteDefinitions.size)
        val footnote = doc.footnoteDefinitions["1"]
        assertEquals("参考论文", footnote?.content)
        assertEquals("https://example.com/paper", footnote?.url)

        val markdown = doc.toMarkdown()
        assertTrue(markdown.contains("[^1]"))
        assertTrue(markdown.contains("[^1]: 参考论文 [https://example.com/paper](https://example.com/paper)"))

        val reparsed = markdownToMdAst(markdown)
        assertEquals(1, reparsed.footnoteDefinitions.size)
        assertEquals("https://example.com/paper", reparsed.footnoteDefinitions["1"]?.url)
    }

    @Test
    fun testStandardMarkdownMediaAndFootnotes() {
        val text = """
            正文[^1]和[^2]

            ![第一张](https://example.com/first.png)

            ![第二张](https://example.com/second.png)

            [^1]: 参考内容1 [链接1](https://example.com/1)
            [^2]: 参考内容2 [链接2](https://example.com/2)
        """.trimIndent()
        val doc = markdownToMdAst(text)
        assertEquals(listOf("https://example.com/first.png", "https://example.com/second.png"), doc.collectImageUrls())
        assertEquals(2, doc.allFootnotes().size)
        assertEquals("https://example.com/1", doc.footnoteDefinitions["1"]?.url)
        assertEquals("https://example.com/2", doc.footnoteDefinitions["2"]?.url)

        val refs = mutableListOf<String>()
        fun findRefs(node: MdNode) {
            if (node is MdFootnoteReference) refs.add(node.label)
            for (child in node.children) findRefs(child)
        }
        findRefs(doc)
        assertEquals(listOf("1", "2"), refs)

        val reparsed = markdownToMdAst(doc.toMarkdown())
        assertEquals(doc.collectImageUrls(), reparsed.collectImageUrls())
        assertEquals(
                    doc.footnoteDefinitions.mapValues { (_, note) -> Triple(note.index, note.content, note.url) },
                    reparsed.footnoteDefinitions.mapValues { (_, note) -> Triple(note.index, note.content, note.url) }
                )
    }
}
