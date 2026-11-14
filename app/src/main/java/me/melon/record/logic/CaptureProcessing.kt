package me.melon.record.logic

import android.util.Log
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RawData_
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.ParserRegistry
import me.melon.record.parser.ParseResult
import me.melon.record.parser.douban.DoubanHotInterestsParser
import me.melon.record.parser.douban.DoubanParser
import me.melon.record.parser.reddit.ObjectBoxRedditReadStorage
import me.melon.record.parser.reddit.RedditParser
import me.melon.record.parser.reddit.RedditReadBuffer
import me.melon.record.parser.threads.ThreadsParser
import me.melon.record.parser.weibo.WeiboParser
import me.melon.record.parser.x.XParser
import me.melon.record.settings.RecordSettings
import me.melon.record.settings.RedditRecordMode

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
    val result: ParseResult = ParseResult.Success(records)
)

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
                for (record in capture.policy.admit(capture)) persistence.persistCompleted(record)
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
        val readPosts = if (readBuffer != null) RedditParser.readPostIds(raw)
            .filterNot { it in advertisingIds.map(RedditParser::postId) }.toSet() else emptySet()
        return ParsedCapture(raw.id, records, this, readPosts, result = result)
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
