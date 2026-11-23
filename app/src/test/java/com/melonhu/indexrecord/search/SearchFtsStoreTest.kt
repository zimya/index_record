package com.melonhu.indexrecord.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchFtsStoreTest {

    private lateinit var store: SearchFtsStore

    @Before
    fun setUp() {
        store = SearchFtsStore(":memory:")
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun fts5SupportsCjkUnigramBigramAndSeparateOcrWriteback() {
        store.upsertRecordsLight(
            listOf(
                RecordTextInput(
                    recordId = 10L,
                    title = "本地搜索架构",
                    author = "张三",
                    bodyText = "使用向量检索与倒排索引优化百万字记录",
                    imageUrls = listOf("https://example.com/a.jpg")
                ),
                RecordTextInput(
                    recordId = 20L,
                    title = "周末旅行随拍",
                    author = "李四",
                    bodyText = "天气很好",
                    imageUrls = listOf("https://example.com/b.jpg")
                )
            )
        )

        val textHits = store.searchTextExact("向量检索")
        assertEquals(listOf(10L), textHits.map { it.recordId })
        assertEquals(listOf(10L), store.searchTextExact("张").map { it.recordId })
        assertTrue(store.searchOcrExact("咖啡馆菜单").isEmpty())

        val pending = store.nextPendingDownloadOrOcrImages(10)
        assertEquals(2, pending.size)
        val img20 = pending.first { it.recordId == 20L }

        // Independent download -> immediate OCR writeback -> later vector result
        store.markImageDownloaded(img20.imageId, IndexStatus.DONE)
        store.markImageOcrResult(
            imageId = img20.imageId,
            recordId = 20L,
            ocrText = "手冲咖啡馆菜单 美式 拿铁",
            ocrStatus = IndexStatus.DONE
        )
        store.markImageVectorResult(img20.imageId, IndexStatus.DONE)

        val ocrHits = store.searchOcrExact("咖啡馆")
        assertEquals(1, ocrHits.size)
        assertEquals(20L, ocrHits[0].recordId)
        assertEquals("https://example.com/b.jpg", ocrHits[0].imageUrl)
        assertTrue(ocrHits[0].ocrText.contains("咖啡馆"))

        assertTrue(store.searchTextExact("咖啡馆").isEmpty())
        assertEquals(listOf(20L), store.searchTextExact("周末旅行").map { it.recordId })
    }

    @Test
    fun metadataFilteringWorksForTextOcrAndCandidateIds() {
        store.upsertRecordsLight(
            listOf(
                RecordTextInput(
                    recordId = 101L,
                    title = "AI 进展",
                    author = "A",
                    bodyText = "大模型推理优化",
                    imageUrls = emptyList(),
                    captureTime = 1000L,
                    platform = "zhihu",
                    recordType = "answer",
                    sourceApp = "com.zhihu.android"
                ),
                RecordTextInput(
                    recordId = 102L,
                    title = "AI 开源",
                    author = "B",
                    bodyText = "大模型本地部署",
                    imageUrls = emptyList(),
                    captureTime = 2000L,
                    platform = "twitter",
                    recordType = "tweet",
                    sourceApp = "com.twitter.android"
                ),
                RecordTextInput(
                    recordId = 103L,
                    title = "AI 评论",
                    author = "C",
                    bodyText = "大模型测试",
                    imageUrls = emptyList(),
                    captureTime = 3000L,
                    platform = "zhihu",
                    recordType = "comment",
                    sourceApp = "com.zhihu.android"
                )
            )
        )

        val timeFiltered = store.searchTextExact(
            query = "大模型",
            filter = SearchFilter(startMillis = 1500L, endMillis = 2500L)
        )
        assertEquals(listOf(102L), timeFiltered.map { it.recordId })

        val appAndTypeFiltered = store.searchTextExact(
            query = "大模型",
            filter = SearchFilter(
                apps = setOf("com.zhihu.android"),
                types = setOf("zhihu:answer")
            )
        )
        assertEquals(listOf(101L), appAndTypeFiltered.map { it.recordId })

        val filteredIds = store.filterRecordIds(
            recordIds = listOf(101L, 102L, 103L),
            filter = SearchFilter(apps = setOf("com.zhihu.android"), startMillis = 2000L)
        )
        assertEquals(setOf(103L), filteredIds)

        // Mark 101 and 103 as text_vec_status = DONE
        store.updateTextVectorStatus(101L, IndexStatus.DONE)
        store.updateTextVectorStatus(103L, IndexStatus.DONE)

        val candidateRecordIds = store.getMatchingRecordIds(
            SearchFilter(
                apps = setOf("com.zhihu.android"),
                types = setOf("zhihu:comment")
            )
        )
        assertEquals(listOf(103L), candidateRecordIds)
    }

    @Test
    fun countsAndFailedRetryTransitionsWorkCleanly() {
        store.upsertRecordLight(
            RecordTextInput(
                recordId = 1L,
                title = "测试",
                author = "作者",
                bodyText = "正文",
                imageUrls = listOf("https://example.com/1.png")
            )
        )

        val initial = store.getIndexingCounts()
        assertEquals(1, initial.pendingText)
        assertEquals(1, initial.pendingDownload)
        assertEquals(1, initial.pendingImageVec)
        assertEquals(3, initial.totalPending)

        store.updateTextVectorStatus(1L, IndexStatus.FAILED)
        val img = store.nextPendingDownloadOrOcrImages(1).single()
        store.markImageDownloaded(img.imageId, IndexStatus.FAILED)

        val failed = store.getIndexingCounts()
        assertEquals(0, failed.totalPending)
        assertEquals(1, failed.failedText)
        assertEquals(1, failed.failedDownload)
        assertEquals(1, failed.failedImageVec)

        store.resetFailedToPending()
        val reset = store.getIndexingCounts()
        assertEquals(1, reset.pendingText)
        assertEquals(1, reset.pendingDownload)
        assertEquals(1, reset.pendingImageVec)
    }
}
