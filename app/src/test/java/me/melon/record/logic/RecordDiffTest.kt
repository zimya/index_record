package me.melon.record.logic

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RecordDiffTest {
    private fun assertReconstructs(old: String, new: String): RecordDiff {
        val diff = recordDiff(old, new)
        assertEquals(old, diff.parts.filter { it.kind != DiffKind.ADDED }.joinToString("") { it.text })
        assertEquals(new, diff.parts.filter { it.kind != DiffKind.REMOVED }.joinToString("") { it.text })
        return diff
    }

    @Test fun emptyAndIdenticalContent() {
        assertTrue(assertReconstructs("", "").parts.isEmpty())
        assertEquals(listOf(DiffPart(DiffKind.UNCHANGED, "相同内容")), recordDiff("相同内容", "相同内容").parts)
        assertReconstructs("", "新增正文")
        assertReconstructs("删除正文", "")
    }

    @Test fun chineseLinesHaveDirectionalChanges() {
        val diff = assertReconstructs("共同标题\n旧正文\n共同结尾", "共同标题\n新正文\n共同结尾")
        assertFalse(diff.coarse)
        assertTrue(diff.parts.contains(DiffPart(DiffKind.REMOVED, "旧正文\n")))
        assertTrue(diff.parts.contains(DiffPart(DiffKind.ADDED, "新正文\n")))
        assertReconstructs("甲\r\n乙\n", "甲\r\n丙")
    }

    @Test fun longSingleChineseLineAndEmojiStayBoundedAndLossless() {
        val old = "中😀".repeat(60_000)
        val diff = assertReconstructs(old, "新🦊".repeat(60_000))
        assertTrue(diff.coarse)
        assertTrue(diff.parts.all { it.text.codePointCount(0, it.text.length) <= 160 })
        assertTrue(diff.parts.none { Character.isLowSurrogate(it.text.first()) || Character.isHighSurrogate(it.text.last()) })
    }

    @Test fun hugeMultilineUsesCoarseReplacementWithoutDroppingSharedEnds() {
        val old = "共同开头\n" + (0..1500).joinToString("\n") { "旧行$it" } + "\n共同结尾"
        val new = "共同开头\n" + (0..1500).joinToString("\n") { "新行$it" } + "\n共同结尾"
        val diff = assertReconstructs(old, new)
        assertTrue(diff.coarse)
        assertEquals(DiffKind.UNCHANGED, diff.parts.first().kind)
        assertEquals(DiffKind.UNCHANGED, diff.parts.last().kind)
    }

    @Test fun repeatedLinesAndRandomInputsReconstructInBothDirections() {
        val random = Random(42)
        repeat(100) {
            fun text() = (0 until random.nextInt(30)).joinToString("") { listOf("甲\n", "乙", "😀\r\n", "甲\n")[random.nextInt(4)] }
            val old = text()
            val new = text()
            assertReconstructs(old, new)
            assertReconstructs(new, old)
        }
    }
}
