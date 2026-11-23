package com.melonhu.indexrecord.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTokenizerTest {

    @Test
    fun cjkEmitsUnigramsAndBigrams() {
        val tokens = SearchTokenizer.tokenizeForIndex("机器学习").split(' ')
        assertEquals(listOf("机", "器", "学", "习", "机器", "器学", "学习"), tokens)
    }

    @Test
    fun mixedCjkAndAlphanumericTokenizesCleanly() {
        val tokens = SearchTokenizer.tokenizeForIndex("iPhone16评测").split(' ')
        assertTrue(tokens.containsAll(listOf("iphone16", "iphone", "16", "评", "测", "评测")))
    }

    @Test
    fun buildFtsQueryHandlesSingleAndMultiCharCjkAndPunctuation() {
        assertNull(SearchTokenizer.buildFtsQuery("   --- ::: \"\" "))

        val single = SearchTokenizer.buildFtsQuery("猫")
        assertEquals("\"猫\"", single)

        val multi = SearchTokenizer.buildFtsQuery("人工智能")
        assertEquals("\"人工\" \"工智\" \"智能\"", multi)

        val mixed = SearchTokenizer.buildFtsQuery("Kotlin 协程")
        assertEquals("\"kotlin\"* \"协程\"", mixed)

        val textQuery = SearchTokenizer.buildTextColumnQuery("向量")
        assertNotNull(textQuery)
        assertTrue(textQuery!!.startsWith("{title_tokens author_tokens body_tokens} :"))

        val ocrQuery = SearchTokenizer.buildOcrColumnQuery("截图")
        assertEquals("ocr_tokens : (\"截图\")", ocrQuery)
    }
}
