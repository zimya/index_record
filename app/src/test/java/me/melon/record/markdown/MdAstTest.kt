package me.melon.record.markdown

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
    fun testCommonMarkFootnotesExtension() {
        val parser = org.commonmark.parser.Parser.builder()
            .extensions(listOf(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.autolink.AutolinkExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
                org.commonmark.ext.footnotes.FootnotesExtension.create()
            ))
            .build()
        val text = """
            正文[^1]和[^2]

            [^1]: 参考内容1 [链接1](https://example.com/1)
            [^2]: 参考内容2 [https://example.com/2](https://example.com/2)
        """.trimIndent()
        val root = parser.parse(text)
        var child = root.firstChild
        while (child != null) {
            println("Block: ${child.javaClass.simpleName}")
            if (child is org.commonmark.ext.footnotes.FootnoteDefinition) {
                println("  FootnoteDefinition label=${child.label}")
                var inChild = child.firstChild
                while (inChild != null) {
                    println("    Footnote child: ${inChild.javaClass.simpleName}")
                    var inInChild = inChild.firstChild
                    while (inInChild != null) {
                        println("      Inline: ${inInChild.javaClass.simpleName} ${(inInChild as? org.commonmark.node.Text)?.literal} ${(inInChild as? org.commonmark.node.Link)?.destination}")
                        inInChild = inInChild.next
                    }
                    inChild = inChild.next
                }
            } else if (child is org.commonmark.node.Paragraph) {
                var inChild = child.firstChild
                while (inChild != null) {
                    println("  Paragraph inline: ${inChild.javaClass.simpleName} ${(inChild as? org.commonmark.ext.footnotes.FootnoteReference)?.label}")
                    inChild = inChild.next
                }
            }
            child = child.next
        }

        // Test with example_record.md
        val file = java.io.File("/Users/hzy/Downloads/example_record.md")
        if (file.exists()) {
            val fileText = file.readText().replace(Regex("""(?<!\n)\s*(\[\^[^\]]+\]:)"""), "\n\n$1")
            val fileRoot = parser.parse(fileText)
            var c = fileRoot.firstChild
            val fns = mutableListOf<String>()
            while (c != null) {
                if (c is org.commonmark.ext.footnotes.FootnoteDefinition) {
                    fns.add(c.label)
                }
                c = c.next
            }
            assertEquals(listOf("1", "2", "3", "4", "5", "6"), fns)
        }
    }

    @Test
    fun testExampleRecord() {
        val file = java.io.File("/Users/hzy/Downloads/example_record.md")
        if (!file.exists()) return
        val text = file.readText()
        val doc = markdownToMdAst(text)

        // 1. Verify images
        val imageUrls = doc.collectImageUrls()
        println("Collected ${imageUrls.size} images:")
        imageUrls.forEach { println("  - $it") }
        assertEquals(18, imageUrls.size)

        // 2. Verify footnote definitions
        val footnotes = doc.allFootnotes()
        println("Parsed ${footnotes.size} footnotes:")
        footnotes.forEach { println("  [${it.label}]: content=${it.content} url=${it.url}") }
        assertEquals(6, footnotes.size)
        assertEquals("https://www.cnblogs.com/8hao/p/5383143.html", doc.footnoteDefinitions["1"]?.url)
        assertEquals("https://timyang.net/architecture/wechat-red-packet/", doc.footnoteDefinitions["2"]?.url)
        assertEquals("https://www.zhihu.com/question/22625187/answer/85530416", doc.footnoteDefinitions["3"]?.url)
        assertEquals("https://www.phppan.com/2015/04/weixin-hongbao/", doc.footnoteDefinitions["4"]?.url)
        assertEquals("https://www.zybuluo.com/yulin718/note/93148", doc.footnoteDefinitions["5"]?.url)
        assertEquals("https://mp.weixin.qq.com/s/5YV4QsK6zXzx-_CdaeLutA", doc.footnoteDefinitions["6"]?.url)

        // 3. Verify footnote references in text
        val refs = mutableListOf<String>()
        fun findRefs(node: MdNode) {
            if (node is MdFootnoteReference) refs.add(node.label)
            for (c in node.children) findRefs(c)
        }
        findRefs(doc)
        println("Found references in AST: $refs")
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), refs)
    }
}
