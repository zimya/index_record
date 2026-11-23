package com.melonhu.indexrecord.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrfFusionTest {

    @Test
    fun multiRouteHitsRankHigherAndPreserveSources() {
        val fused = RrfFusion.fuse(
            textExactRecordIds = listOf(1L, 2L),
            ocrExactHits = listOf(
                OcrFtsHit(recordId = 2L, bm25Score = -3.0, ocrText = "图内关键字", imageUrl = "https://img/2.jpg")
            ),
            textSemanticHits = listOf(
                VectorHit(id = 3L, similarity = 0.85f),
                VectorHit(id = 2L, similarity = 0.78f)
            ),
            imageVisualHits = listOf(
                IndexedImageInfo(10L, 4L, "https://img/4a.jpg", "") to 0.81f,
                IndexedImageInfo(11L, 4L, "https://img/4b.jpg", "") to 0.72f
            )
        )

        // Record 2 matched 3 routes (TEXT_EXACT rank 2, OCR_EXACT rank 1, TEXT_SEMANTIC rank 2) -> top rank
        assertEquals(2L, fused.first().recordId)
        assertEquals(
            setOf(
                SearchMatchSource.TEXT_EXACT,
                SearchMatchSource.OCR_EXACT,
                SearchMatchSource.TEXT_SEMANTIC
            ),
            fused.first().sources
        )
        assertEquals("图内关键字", fused.first().matchedOcrText)
        assertEquals("https://img/2.jpg", fused.first().matchedImageUrl)
        assertEquals("https://img/2.jpg", fused.first().matchedOcrImageUrl)
        assertEquals(null, fused.first().matchedVisualImageUrl)

        // Record 4 had 2 matching images; deduplicated to 1 entry keeping best image URL and preserving all matched images
        val record4 = fused.first { it.recordId == 4L }
        assertEquals(setOf(SearchMatchSource.IMAGE_VISUAL), record4.sources)
        assertEquals("https://img/4a.jpg", record4.matchedImageUrl)
        assertEquals("https://img/4a.jpg", record4.matchedVisualImageUrl)
        assertEquals(null, record4.matchedOcrImageUrl)
        assertEquals(0.81f, record4.visualSimilarity)
        assertEquals(2, record4.matchedVisualHits.size)
        assertEquals("https://img/4a.jpg", record4.matchedVisualHits[0].imageUrl)
        assertEquals(0.81f, record4.matchedVisualHits[0].similarity)
        assertEquals("https://img/4b.jpg", record4.matchedVisualHits[1].imageUrl)
        assertEquals(0.72f, record4.matchedVisualHits[1].similarity)
        assertTrue(fused.map { it.recordId }.toSet() == setOf(1L, 2L, 3L, 4L))
    }

    @Test
    fun multiImageOcrHitsPreservesAllImagesInList() {
        val fused = RrfFusion.fuse(
            textExactRecordIds = emptyList(),
            ocrExactHits = listOf(
                OcrFtsHit(
                    recordId = 8L,
                    bm25Score = -2.0,
                    matchedImages = listOf(
                        MatchedOcrImage(101L, "https://img/8a.jpg", "OCR 命中 A"),
                        MatchedOcrImage(102L, "https://img/8b.jpg", "OCR 命中 B")
                    )
                )
            ),
            textSemanticHits = emptyList(),
            imageVisualHits = emptyList()
        )

        assertEquals(1, fused.size)
        val c = fused.first()
        assertEquals(8L, c.recordId)
        assertEquals(2, c.matchedOcrHits.size)
        assertEquals("https://img/8a.jpg", c.matchedOcrHits[0].imageUrl)
        assertEquals("OCR 命中 A", c.matchedOcrHits[0].ocrText)
        assertEquals("https://img/8b.jpg", c.matchedOcrHits[1].imageUrl)
        assertEquals("OCR 命中 B", c.matchedOcrHits[1].ocrText)
    }

    @Test
    fun visualHitsDoNotLeakOcrTextWhenOcrExactNotMatched() {
        val fused = RrfFusion.fuse(
            textExactRecordIds = emptyList(),
            ocrExactHits = emptyList(),
            textSemanticHits = emptyList(),
            imageVisualHits = listOf(
                IndexedImageInfo(10L, 5L, "https://img/5.jpg", "无关图片OCR文字") to 0.79f
            )
        )

        assertEquals(1, fused.size)
        val candidate = fused.first()
        assertEquals(5L, candidate.recordId)
        assertEquals(setOf(SearchMatchSource.IMAGE_VISUAL), candidate.sources)
        assertEquals(null, candidate.matchedOcrText)
        assertEquals(null, candidate.matchedOcrImageUrl)
        assertEquals("https://img/5.jpg", candidate.matchedVisualImageUrl)
        assertEquals(0.79f, candidate.visualSimilarity)
    }
}
