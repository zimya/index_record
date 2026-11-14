package com.melonhu.indexrecord.markdown

import com.google.gson.JsonElement
import com.melonhu.indexrecord.parser.zhihu.markdown.htmlToMdAst as zhihuHtmlToMdAst
import com.melonhu.indexrecord.parser.zhihu.markdown.zhihuHtmlToMarkdown as internalZhihuHtmlToMarkdown
import com.melonhu.indexrecord.parser.zhihu.markdown.zhihuStructuredContentToMarkdown as internalZhihuStructuredContentToMarkdown
import com.melonhu.indexrecord.parser.zhihu.markdown.extractImageUrl as internalExtractImageUrl

/**
 * 兼容层与统一门面：提供知乎 HTML / 卡片内容转换以及通用 Markdown 解析入口。
 * 具体实现已按职责拆分至：
 * - [MdSerializer] 通用 AST -> Markdown 序列化
 * - [MdParser] 通用 Markdown -> AST 解析器
 * - [com.melonhu.indexrecord.parser.zhihu.markdown.ZhihuHtmlConverter] 知乎 HTML 解析
 * - [com.melonhu.indexrecord.parser.zhihu.markdown.ZhihuStructuredConverter] 知乎卡片模式解析
 */

fun htmlToMdAst(html: String): MdDocument = zhihuHtmlToMdAst(html)

fun zhihuHtmlToMarkdown(html: String): String = internalZhihuHtmlToMarkdown(html)

fun zhihuStructuredContentToMarkdown(scElement: JsonElement?): String =
    internalZhihuStructuredContentToMarkdown(scElement)

fun extractImageUrl(attribute: (String) -> String): String? = internalExtractImageUrl(attribute)
