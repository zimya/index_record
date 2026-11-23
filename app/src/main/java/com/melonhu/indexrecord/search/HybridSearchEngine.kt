package com.melonhu.indexrecord.search

import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bodyPlainText
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal enum class SearchMatchSource {
    TEXT_EXACT,
    OCR_EXACT,
    TEXT_SEMANTIC,
    IMAGE_VISUAL
}

internal data class MatchedOcrItem(
    val imageUrl: String,
    val snippet: String
)

internal data class MatchedVisualItem(
    val imageUrl: String,
    val similarity: Float
)

internal data class SearchResultItem(
    val record: UniversalRecord,
    val rrfScore: Double,
    val sources: Set<SearchMatchSource>,
    val textExactSnippet: String? = null,
    val textSemanticSnippet: String? = null,
    val textSimilarity: Float? = null,
    val matchedOcrItems: List<MatchedOcrItem> = emptyList(),
    val matchedVisualItems: List<MatchedVisualItem> = emptyList()
) {
    val matchedOcrSnippet: String?
        get() = matchedOcrItems.firstOrNull()?.snippet
    val matchedOcrImageUrl: String?
        get() = matchedOcrItems.firstOrNull()?.imageUrl
    val matchedVisualImageUrl: String?
        get() = matchedVisualItems.firstOrNull()?.imageUrl
    val visualSimilarity: Float?
        get() = matchedVisualItems.firstOrNull()?.similarity
    val matchedImageUrl: String?
        get() = matchedVisualImageUrl ?: matchedOcrImageUrl
}

internal data class HybridSearchOutput(
    val query: String,
    val highlightTerms: List<String>,
    val results: List<SearchResultItem>,
    val usedVectorSearch: Boolean,
    val errorMessage: String? = null
)

internal object RrfFusion {
    const val DEFAULT_K = 60.0
    const val WEIGHT_TEXT_EXACT = 1.25
    const val WEIGHT_OCR_EXACT = 1.15
    const val WEIGHT_TEXT_SEMANTIC = 1.0
    const val WEIGHT_IMAGE_VISUAL = 1.0

    data class FusedCandidate(
        val recordId: Long,
        val score: Double,
        val sources: Set<SearchMatchSource>,
        val textSimilarity: Float? = null,
        val matchedOcrHits: List<MatchedOcrImage> = emptyList(),
        val matchedVisualHits: List<MatchedVisualItem> = emptyList()
    ) {
        val matchedOcrText: String?
            get() = matchedOcrHits.firstOrNull()?.ocrText
        val matchedOcrImageUrl: String?
            get() = matchedOcrHits.firstOrNull()?.imageUrl
        val matchedVisualImageUrl: String?
            get() = matchedVisualHits.firstOrNull()?.imageUrl
        val visualSimilarity: Float?
            get() = matchedVisualHits.firstOrNull()?.similarity
        val matchedImageUrl: String?
            get() = matchedVisualImageUrl ?: matchedOcrImageUrl
    }

    fun fuse(
        textExactRecordIds: List<Long>,
        ocrExactHits: List<OcrFtsHit>,
        textSemanticHits: List<VectorHit>,
        imageVisualHits: List<Pair<IndexedImageInfo, Float>>,
        k: Double = DEFAULT_K
    ): List<FusedCandidate> {
        class Accumulator(val recordId: Long) {
            var score: Double = 0.0
            val sources = linkedSetOf<SearchMatchSource>()
            var textSimilarity: Float? = null
            val ocrHits = ArrayList<MatchedOcrImage>()
            val visualHits = ArrayList<MatchedVisualItem>()
        }

        val byId = LinkedHashMap<Long, Accumulator>()
        fun acc(id: Long): Accumulator = byId.getOrPut(id) { Accumulator(id) }

        textExactRecordIds.forEachIndexed { idx, recordId ->
            val a = acc(recordId)
            a.score += WEIGHT_TEXT_EXACT / (k + idx + 1)
            a.sources.add(SearchMatchSource.TEXT_EXACT)
        }

        ocrExactHits.forEachIndexed { idx, hit ->
            val a = acc(hit.recordId)
            a.score += WEIGHT_OCR_EXACT / (k + idx + 1)
            a.sources.add(SearchMatchSource.OCR_EXACT)
            for (m in hit.matchedImages) {
                if (m.imageUrl.isNotBlank() || m.ocrText.isNotBlank()) {
                    if (a.ocrHits.none { it.imageUrl == m.imageUrl && it.ocrText == m.ocrText }) {
                        a.ocrHits.add(m)
                    }
                }
            }
        }

        val seenTextSem = HashSet<Long>()
        var textSemRank = 0
        for (hit in textSemanticHits) {
            val isFirst = seenTextSem.add(hit.id)
            if (isFirst) {
                textSemRank++
                val a = acc(hit.id)
                a.score += WEIGHT_TEXT_SEMANTIC / (k + textSemRank)
                a.sources.add(SearchMatchSource.TEXT_SEMANTIC)
                if (a.textSimilarity == null) {
                    a.textSimilarity = hit.similarity
                }
            }
        }

        val seenImgVis = HashSet<Long>()
        var imgVisRank = 0
        for ((info, similarity) in imageVisualHits) {
            val isFirstForRecord = seenImgVis.add(info.recordId)
            if (isFirstForRecord) {
                imgVisRank++
                val a = acc(info.recordId)
                a.score += WEIGHT_IMAGE_VISUAL / (k + imgVisRank)
                a.sources.add(SearchMatchSource.IMAGE_VISUAL)
            }
            if (info.imageUrl.isNotBlank()) {
                val a = acc(info.recordId)
                if (a.visualHits.none { it.imageUrl == info.imageUrl }) {
                    a.visualHits.add(MatchedVisualItem(info.imageUrl, similarity))
                }
            }
        }

        return byId.values
            .sortedByDescending { it.score }
            .map {
                FusedCandidate(
                    recordId = it.recordId,
                    score = it.score,
                    sources = it.sources,
                    textSimilarity = it.textSimilarity,
                    matchedOcrHits = it.ocrHits,
                    matchedVisualHits = it.visualHits
                )
            }
    }
}

internal class HybridSearchEngine(
    private val ftsStore: SearchFtsStore,
    private val vectorStore: VectorIndexStore,
    private val modelManager: EmbeddingModelManager,
    private val store: BoxStore = ObjectBox.store
) {
    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter(),
        limit: Int = DEFAULT_RESULT_LIMIT
    ): HybridSearchOutput = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || filter.sources.isEmpty()) {
            return@withContext HybridSearchOutput(
                query = trimmed,
                highlightTerms = emptyList(),
                results = emptyList(),
                usedVectorSearch = false
            )
        }

        val terms = SearchTokenizer.extractHighlightTerms(trimmed)
        val runTextExact = SearchMatchSource.TEXT_EXACT in filter.sources
        val runOcrExact = SearchMatchSource.OCR_EXACT in filter.sources
        val runTextSemantic = SearchMatchSource.TEXT_SEMANTIC in filter.sources
        val runImageVisual = SearchMatchSource.IMAGE_VISUAL in filter.sources

        coroutineScope {
            // Route A: Exact word-frequency FTS5 search (with SQL metadata filtering)
            val textExactDeferred = async {
                if (runTextExact) ftsStore.searchTextExact(trimmed, ROUTE_TOP_K, filter) else emptyList()
            }
            val ocrExactDeferred = async {
                if (runOcrExact) ftsStore.searchOcrExact(trimmed, ROUTE_TOP_K, filter) else emptyList()
            }

            // Route B: Semantic & Visual top-K via EmbeddingGemma 2 + USearch
            var semanticError: String? = null
            val vectorDeferred = async {
                if (!filter.hasSemanticSource) {
                    null
                } else if (!modelManager.isModelDownloaded()) {
                    if (!runTextExact && !runOcrExact) {
                        semanticError = MODEL_NOT_DOWNLOADED_ERROR
                    }
                    null
                } else if (vectorStore.size() == 0L) {
                    null
                } else {
                    val queryVec = modelManager.embedQuery(trimmed)
                    if (queryVec == null) {
                        semanticError = modelManager.lastError ?: "语义向量模型未就绪"
                        return@async null
                    }
                    val textHits = if (runTextSemantic) {
                        if (filter.hasMetadataFilter) {
                            val candidateIds = ftsStore.getMatchingRecordIds(filter)
                            if (candidateIds.isNotEmpty()) {
                                vectorStore.searchTextInKeys(queryVec, candidateIds.toLongArray(), ROUTE_TOP_K)
                                    .filter { it.similarity >= MIN_SEMANTIC_SIMILARITY }
                            } else emptyList()
                        } else {
                            vectorStore.searchTextTopK(queryVec, ROUTE_TOP_K)
                                .filter { it.similarity >= MIN_SEMANTIC_SIMILARITY }
                        }
                    } else emptyList()

                    val resolvedImgHits = if (runImageVisual) {
                        val imgHits = if (filter.hasMetadataFilter) {
                            val candidateImgIds = ftsStore.getMatchingImageIds(filter)
                            if (candidateImgIds.isNotEmpty()) {
                                vectorStore.searchImageInKeys(queryVec, candidateImgIds.toLongArray(), ROUTE_TOP_K)
                                    .filter { it.similarity >= MIN_VISUAL_SIMILARITY }
                            } else emptyList()
                        } else {
                            vectorStore.searchImageTopK(queryVec, ROUTE_TOP_K)
                                .filter { it.similarity >= MIN_VISUAL_SIMILARITY }
                        }
                        if (imgHits.isNotEmpty()) {
                            val imgInfoMap = ftsStore.getImageInfoByIds(imgHits.map { it.id })
                            imgHits.mapNotNull { hit ->
                                val info = imgInfoMap[hit.id] ?: return@mapNotNull null
                                info to hit.similarity
                            }
                        } else emptyList()
                    } else emptyList()

                    textHits to resolvedImgHits
                }
            }

            val textExact = textExactDeferred.await()
            val ocrExact = ocrExactDeferred.await()
            val vectorPair = vectorDeferred.await()

            val fused = RrfFusion.fuse(
                textExactRecordIds = textExact.map { it.recordId },
                ocrExactHits = ocrExact,
                textSemanticHits = vectorPair?.first.orEmpty(),
                imageVisualHits = vectorPair?.second.orEmpty()
            ).take(limit)

            if (fused.isEmpty()) {
                return@coroutineScope HybridSearchOutput(
                    query = trimmed,
                    highlightTerms = terms,
                    results = emptyList(),
                    usedVectorSearch = vectorPair != null,
                    errorMessage = semanticError
                )
            }

            // Batch lookup from ObjectBox by ID
            val recordBox = store.boxFor(UniversalRecord::class.java)
            val recordsById = recordBox.get(fused.map { it.recordId }).associateBy { it.id }

            val items = fused.mapNotNull { candidate ->
                val record = recordsById[candidate.recordId] ?: return@mapNotNull null
                val isTextExactMatched = SearchMatchSource.TEXT_EXACT in candidate.sources
                val isTextSemanticMatched = SearchMatchSource.TEXT_SEMANTIC in candidate.sources
                val isOcrMatched = SearchMatchSource.OCR_EXACT in candidate.sources
                val isVisualMatched = SearchMatchSource.IMAGE_VISUAL in candidate.sources

                val bodyPlain = record.bodyPlainText().ifBlank { record.content }

                val textExactSnippet = if (isTextExactMatched) {
                    extractTextSnippet(bodyPlain, terms)
                } else null

                val textSemanticSnippet = if (isTextSemanticMatched) {
                    extractTextSnippet(bodyPlain, terms)
                } else null

                val ocrItems = if (isOcrMatched) {
                    candidate.matchedOcrHits.mapNotNull { hit ->
                        val snippet = extractTextSnippet(hit.ocrText, terms)
                        if (snippet.isNotBlank() || hit.imageUrl.isNotBlank()) {
                            MatchedOcrItem(imageUrl = hit.imageUrl, snippet = snippet)
                        } else null
                    }
                } else emptyList()

                val visualItems = if (isVisualMatched) {
                    candidate.matchedVisualHits
                } else emptyList()

                SearchResultItem(
                    record = record,
                    rrfScore = candidate.score,
                    sources = candidate.sources,
                    textExactSnippet = textExactSnippet,
                    textSemanticSnippet = textSemanticSnippet,
                    textSimilarity = candidate.textSimilarity,
                    matchedOcrItems = ocrItems,
                    matchedVisualItems = visualItems
                )
            }

            HybridSearchOutput(
                query = trimmed,
                highlightTerms = terms,
                results = items,
                usedVectorSearch = vectorPair != null
            )
        }
    }

    private fun extractTextSnippet(text: String, terms: List<String>): String {
        val compact = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (compact.length <= 120) return compact
        for (term in terms) {
            val idx = compact.indexOf(term, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 35).coerceAtLeast(0)
                val end = (idx + term.length + 65).coerceAtMost(compact.length)
                return (if (start > 0) "…" else "") +
                    compact.substring(start, end) +
                    (if (end < compact.length) "…" else "")
            }
        }
        return compact.take(120) + "…"
    }

    companion object {
        const val MODEL_NOT_DOWNLOADED_ERROR = "__MODEL_NOT_DOWNLOADED__"
        private const val ROUTE_TOP_K = 40
        private const val DEFAULT_RESULT_LIMIT = 60
        private const val MIN_SEMANTIC_SIMILARITY = 0.20f
        private const val MIN_VISUAL_SIMILARITY = 0.18f
    }
}
