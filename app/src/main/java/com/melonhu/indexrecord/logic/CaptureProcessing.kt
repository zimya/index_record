package com.melonhu.indexrecord.logic

import android.util.Log
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RawData_
import com.melonhu.indexrecord.data.model.RedditPendingRecord
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.douban.DoubanHotInterestsParser
import com.melonhu.indexrecord.parser.douban.DoubanParser
import com.melonhu.indexrecord.parser.reddit.ObjectBoxRedditReadStorage
import com.melonhu.indexrecord.parser.reddit.RedditParser
import com.melonhu.indexrecord.parser.reddit.RedditReadBuffer
import com.melonhu.indexrecord.parser.reddit.RedditReadVisit
import com.melonhu.indexrecord.parser.threads.ThreadsParser
import com.melonhu.indexrecord.parser.bluesky.BlueskyParser
import com.melonhu.indexrecord.parser.weibo.WeiboParser
import com.melonhu.indexrecord.parser.x.XParser
import com.melonhu.indexrecord.settings.RecordSettings
import com.melonhu.indexrecord.settings.RedditRecordMode

/** Parsing may run outside a transaction; admission MUST run inside the raw-delete transaction. */
internal interface CapturePolicy {
    fun canHandle(raw: RawData, cleanUrl: String): Boolean

    fun prepare(scan: ((RawData) -> Unit) -> Unit) {}
    fun parse(raw: RawData, cleanUrl: String): ParsedCapture
    fun admit(capture: ParsedCapture): List<UniversalRecord> = capture.records
}

internal data class ParsedCapture(
    val rawId: Long,
    val records: List<UniversalRecord>,
    val policy: CapturePolicy,
    val readPosts: Set<String> = emptySet(),
    val isCompleted: Boolean = false,
    val completion: ((RecordPersistence) -> Unit)? = null,
    val result: ParseResult = ParseResult.Success(records),
    // Snapshot at construction, before platform completion mutates the parser's entities.
    val parsedRecords: List<UniversalRecord> = records.map { it.copy() },
    val readVisits: List<RedditReadVisit> = emptyList()
)

internal fun rawSnapshotKey(rawId: Long, record: UniversalRecord, occurrence: Int): String =
    "raw:$rawId:${record.platform.length}:${record.platform}:${record.platformId}:$occurrence"

internal class CaptureProcessing(
    private val policies: List<CapturePolicy>,
    private val scan: ((RawData) -> Unit) -> Unit = {}
) {
    /** Called once before ANY chunk: sparse details can precede their advertising context. */
    fun prepare() { policies.forEach { it.prepare(scan) } }

    /** Complete each capture before parsing the next one, while the entire chunk is still unwritten. */
    fun parse(raw: RawData, persistence: RecordPersistence? = null, onError: (Exception) -> Unit): ParsedCapture {
        val cleanUrl = if (raw.url.contains("?")) raw.url.substringBefore("?") else raw.url
        val policy = policies.first { it.canHandle(raw, cleanUrl) }
        return try {
            val capture = policy.parse(raw, cleanUrl)
            capture.result.diagnostics.forEach { diagnostic ->
                val error = diagnostic.cause ?: IllegalArgumentException("${diagnostic.stage}: ${diagnostic.message}")
                persistence?.completeFailure(error)
                onError(error)
            }
            if (persistence != null) complete(capture, persistence) else capture
        } catch (e: Exception) {
            persistence?.completeFailure(e)
            onError(e)
            ParsedCapture(raw.id, emptyList(), policy, isCompleted = persistence != null,
                result = ParseResult.failure("capture.parseOrComplete", e))
        }
    }

    fun complete(capture: ParsedCapture, persistence: RecordPersistence): ParsedCapture {
        check(!capture.isCompleted) { "Capture ${capture.rawId} was already completed" }
        val completion = capture.completion
        if (capture.result !is ParseResult.Failure && capture.result !is ParseResult.Ignored) {
            if (completion != null) completion(persistence) else persistence.completeChunk(capture.records)
        }
        return capture.copy(isCompleted = true)
    }

    /** Caller owns the transaction, including deletion. Never reorder captures or records. */
    fun persist(captures: List<ParsedCapture>, persistence: RecordPersistence): LongArray {
        // Completion has already run, capture by capture, against the pre-write view. Repeating it
        // here would expose earlier captures to later title evidence and change metadata timing.
        check(captures.all { it.isCompleted }) { "Complete every capture before starting chunk writes" }
        val processedRawIds = ArrayList<Long>(captures.size)
        for (capture in captures) {
            if (capture.result !is ParseResult.Failure && capture.result !is ParseResult.Ignored) {
                val redditRead = (capture.policy as? RedditCapturePolicy)?.isReadMode == true
                if (redditRead) persistence.captureRedditPostVisits(
                    capture.rawId, capture.parsedRecords, capture.readVisits, System.currentTimeMillis()
                )
                val replayed = if (redditRead) persistence.stageRedditHistory(capture.rawId, capture.parsedRecords)
                    else emptySet()
                val admitted = capture.policy.admit(capture)
                if (redditRead) {
                    admitted.forEach { persistence.persistRedditAdmitted(it, it.identity in replayed) }
                } else {
                    val originals = capture.parsedRecords.groupBy { it.platform to it.platformId }
                    val occurrences = mutableMapOf<Pair<String, String>, Int>()
                    for (record in admitted) {
                        val role = record.platform to record.platformId
                        val occurrence = occurrences.getOrDefault(role, 0)
                        occurrences[role] = occurrence + 1
                        val original = originals[role]?.getOrNull(occurrence) ?: record.copy()
                        val key = rawSnapshotKey(capture.rawId, record, occurrence)
                        persistence.persistCompleted(record, original, key)
                    }
                }
            }
            if (capture.result.consumeRaw) processedRawIds.add(capture.rawId)
        }
        return processedRawIds.toLongArray()
    }
}

internal class FilteredCapturePolicy(
    private val handles: (String, RawData) -> Boolean,
    private val shouldProcess: (String) -> Boolean
) : CapturePolicy {
    override fun canHandle(raw: RawData, cleanUrl: String) = handles(cleanUrl, raw)
    override fun parse(raw: RawData, cleanUrl: String): ParsedCapture {
        // 在本应用进程读取设置，避免 Hook 跨进程读取私有偏好设置。
        val result = if (shouldProcess(raw.url)) ParserRegistry.parseResult(raw, cleanUrl)
            else ParseResult.Ignored("Disabled by recording mode")
        val records = result.records
        if (raw.hostApp == "com.instagram.barcelona") {
            Log.i("DataProcessor", "Threads parsed ${records.size} records from $cleanUrl (${raw.json.length} chars)")
        }
        return ParsedCapture(raw.id, records, this, result = result)
    }
}

internal class RedditCapturePolicy(
    private val readBuffer: RedditReadBuffer?,
    private val now: () -> Long = System::currentTimeMillis
) : CapturePolicy {
    internal val isReadMode: Boolean get() = readBuffer != null
    private val advertisingIds = linkedSetOf<String>()

    override fun canHandle(raw: RawData, cleanUrl: String) = RedditParser.canHandle(cleanUrl, raw)
    override fun prepare(scan: ((RawData) -> Unit) -> Unit) {
        advertisingIds.clear()
        // Older debug captures were not projected at the hook. Learn ad context from every
        // pending Reddit payload before a sparse detail can be mistaken for an organic post.
        scan { advertisingIds.addAll(RedditParser.advertisingPostIds(it)) }
    }
    override fun parse(raw: RawData, cleanUrl: String): ParsedCapture {
        // Parse every fragment; READ admission happens transactionally in the persistent buffer.
        val result = RedditParser.parseResultWithAdvertisingIds(raw, cleanUrl, advertisingIds)
        val records = result.records
        val visits = if (readBuffer != null) RedditParser.readVisits(raw)
            .filterNot { it.postId in advertisingIds.map(RedditParser::postId) } else emptyList()
        val readPosts = visits.mapTo(linkedSetOf()) { it.postId }
        return ParsedCapture(raw.id, records, this, readPosts, result = result, readVisits = visits)
    }
    override fun admit(capture: ParsedCapture): List<UniversalRecord> =
        readBuffer?.accept(capture.records, capture.readPosts, now()) ?: capture.records
}

internal object DoubanCapturePolicy : CapturePolicy {
    override fun canHandle(raw: RawData, cleanUrl: String) = DoubanParser.canHandle(cleanUrl, raw)
    override fun parse(raw: RawData, cleanUrl: String): ParsedCapture {
        if (!DoubanHotInterestsParser.canHandle(cleanUrl)) {
            val result = DoubanParser.parseResult(raw, cleanUrl)
            return ParsedCapture(raw.id, result.records, this, result = result)
        }
        // Validate the envelope before extracting platform-owned title completion evidence.
        require(com.google.gson.JsonParser.parseString(raw.json).isJsonObject) { "Expected Douban object" }
        val result = DoubanHotInterestsParser.parseWithTitleEvidence(raw, cleanUrl)
        return ParsedCapture(raw.id, result.records, this, completion = { persistence ->
            persistence.complete(result.completionEvidence())
        })
    }
}

internal object RegistryCapturePolicy : CapturePolicy {
    override fun canHandle(raw: RawData, cleanUrl: String) = true
    override fun parse(raw: RawData, cleanUrl: String): ParsedCapture {
        val result = ParserRegistry.parseResult(raw, cleanUrl)
        return ParsedCapture(raw.id, result.records, this, result = result)
    }
}

/** One session per processing run: no advertising or READ-mode state leaks into the next run. */
internal fun objectBoxCaptureProcessing(chunkSize: Long): CaptureProcessing {
    val buffer = if (RecordSettings.redditRecordMode == RedditRecordMode.READ) {
        RedditReadBuffer(ObjectBoxRedditReadStorage(ObjectBox.store.boxFor(RedditPendingRecord::class.java)))
    } else null
    return CaptureProcessing(listOf(
        FilteredCapturePolicy(XParser::canHandle) { RecordSettings.tweetRecordMode.shouldProcess(it) },
        FilteredCapturePolicy(WeiboParser::canHandle) { RecordSettings.weiboRecordMode.shouldProcess(it) },
        FilteredCapturePolicy(ThreadsParser::canHandle) { RecordSettings.threadsRecordMode.shouldProcess(it) },
        FilteredCapturePolicy(BlueskyParser::canHandle) { RecordSettings.blueskyRecordMode.shouldProcess(it) },
        RedditCapturePolicy(buffer), DoubanCapturePolicy, RegistryCapturePolicy
    )) { consume -> scanPendingReddit(chunkSize, consume) }
}

/** Database-backed preflight belongs to the platform implementation, not the chunk coordinator. */
private fun scanPendingReddit(chunkSize: Long, consume: (RawData) -> Unit) {
    val rawBox = ObjectBox.store.boxFor(RawData::class.java)
    val query = rawBox.query().equal(RawData_.hostApp, RedditParser.HOST_APP,
        io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE).build()
    try {
        var offset = 0L
        while (true) {
            val items = query.find(offset, chunkSize)
            if (items.isEmpty()) break
            items.forEach(consume)
            offset += items.size
        }
    } finally { query.close() }
}
