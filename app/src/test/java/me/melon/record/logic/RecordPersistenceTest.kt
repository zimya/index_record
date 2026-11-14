package me.melon.record.logic

import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanGroupTopicParser
import me.melon.record.parser.douban.DoubanGroupTopicCommentsParser
import me.melon.record.parser.douban.DoubanHotInterestsParser
import me.melon.record.parser.douban.DoubanSubjectParser
import me.melon.record.parser.zhihu.ZhihuAnswerParser
import me.melon.record.parser.zhihu.ZhihuItemV2Parser
import org.junit.Assert.*
import org.junit.Test

/** Observe completion through its public behavior, not the collaborator's private title cache. */
internal fun RecordPersistenceContext.doubanTitle(subjectId: String): String {
    val record = UniversalRecord(
        platform = "douban", recordType = "interest",
        extraMetadata = """{"subject_id":"$subjectId","subject_type":"movie"}"""
    )
    RecordPersistence(MemoryRecordRepository(), this).completeChunk(listOf(record))
    return JsonParser.parseString(record.extraMetadata).asJsonObject.get("subject_title")?.asString.orEmpty()
}

internal class MemoryRecordRepository : RecordRepository {
    val rows = linkedMapOf<String, UniversalRecord>()
    val writes = mutableListOf<String>()
    var failOnPut = false
    var failOnWrite: Int? = null
    val reads = mutableListOf<String>()
    override fun find(platformId: String): UniversalRecord? {
        reads.add(platformId)
        return rows[platformId]?.copy()
    }
    override fun children(parentId: String) = rows.values.filter { it.parentId == parentId }.map { it.copy() }
    override fun unboundZhihuAnswers() = rows.values.filter {
        it.platform == "zhihu" && it.recordType == "answer" && it.parentId.isBlank()
    }.map { it.copy() }
    override fun findIds(platformIds: List<String>) = rows.values.filter { it.platformId in platformIds }.map { it.copy() }
    override fun put(record: UniversalRecord) {
        check(!failOnPut && writes.size + 1 != failOnWrite) { "injected write failure" }
        if (record.id == 0L) record.id = (rows.size + 1).toLong()
        rows[record.platformId] = record.copy()
        writes.add(record.platformId)
    }

}

class RecordPersistenceTest {
    private val repository = MemoryRecordRepository()
    private val persistence = RecordPersistence(repository)
    private fun answer(id: String = "a", qid: String = "q", parent: String = "") = UniversalRecord(
        platform = "zhihu", recordType = "answer", platformId = "zhihu_answer_$id", parentId = parent,
        extraMetadata = """{"question_id":"$qid"}"""
    )
    private fun question(id: String = "q", refs: String = "[]") = UniversalRecord(
        platform = "zhihu", recordType = "question", platformId = "zhihu_question_$id",
        extraMetadata = """{"question_id":"$id","answer_refs":$refs}"""
    )
    private fun interest(id: String = "i", subject: String = "987654321", type: String = "movie") = UniversalRecord(
        platform = "douban", recordType = "interest", platformId = "douban_interest_$id",
        parentId = "douban_${type}_$subject", title = "电影短评：$subject",
        extraMetadata = """{"subject_id":"$subject","subject_type":"$type","vote_count":3}"""
    )
    private fun subject(title: String = "标题", type: String = "movie") = UniversalRecord(
        platform = "douban", recordType = "subject", platformId = "douban_${type}_987654321", title = title,
        extraMetadata = """{"subject_id":"987654321","subject_type":"$type","subject_title":"$title"}"""
    )
    private fun meta(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject

    private fun tweet(id: String, type: String = "tweet", parent: String = "") = UniversalRecord(
        platform = "twitter", recordType = type, platformId = "twitter_${type}_$id", parentId = parent
    )

    @Test fun twitterReplyLinksToEitherExistingParentRoleWithoutOverridingExactMatch() {
        persistence.persist(tweet("p"))
        persistence.persist(tweet("a", "comment", "twitter_comment_p"))
        assertEquals("twitter_tweet_p", repository.find("twitter_comment_a")!!.parentId)
        persistence.persist(tweet("c", "comment"))
        persistence.persist(tweet("b", "comment", "twitter_tweet_c"))
        assertEquals("twitter_comment_c", repository.find("twitter_comment_b")!!.parentId)
        persistence.persist(tweet("p", "comment"))
        persistence.persist(tweet("exact", "comment", "twitter_comment_p"))
        assertEquals("twitter_comment_p", repository.find("twitter_comment_exact")!!.parentId)
    }

    @Test fun twitterLateTweetRewritesOnlyMatchingTwitterCommentsAndDoesNotCreateParents() {
        persistence.persist(tweet("a", "comment", "twitter_comment_p"))
        persistence.persist(tweet("b", "comment", "twitter_tweet_p"))
        assertNull(repository.find("twitter_tweet_p"))
        repository.put(tweet("foreign", "comment", "twitter_comment_p").copy(platform = "other"))
        repository.put(tweet("noncomment", parent = "twitter_comment_p"))
        persistence.persist(tweet("p"))
        assertEquals("twitter_tweet_p", repository.find("twitter_comment_a")!!.parentId)
        assertEquals("twitter_tweet_p", repository.find("twitter_comment_b")!!.parentId)
        assertEquals("twitter_comment_p", repository.find("twitter_comment_foreign")!!.parentId)
        assertEquals("twitter_comment_p", repository.find("twitter_tweet_noncomment")!!.parentId)
        // A late comment parent historically does not repair the opposite-role orphan.
        persistence.persist(tweet("orphan", "comment", "twitter_tweet_late"))
        persistence.persist(tweet("late", "comment"))
        assertEquals("twitter_tweet_late", repository.find("twitter_comment_orphan")!!.parentId)
    }

    @Test fun twitterRelationWriteFailuresPropagateForBothArrivalOrders() {
        for (parentFirst in listOf(true, false)) {
            val repo = MemoryRecordRepository()
            val writer = RecordPersistence(repo)
            writer.persist(if (parentFirst) tweet("p") else tweet("a", "comment", "twitter_comment_p"))
            repo.failOnWrite = repo.writes.size + 2 // primary write succeeds; relation write fails
            val error = assertThrows(IllegalStateException::class.java) {
                writer.persist(if (parentFirst) tweet("a", "comment", "twitter_comment_p") else tweet("p"))
            }
            assertEquals("injected write failure", error.message)
            assertEquals("twitter_comment_p", repo.find("twitter_comment_a")!!.parentId)
        }
    }

    @Test fun twitterRelationReadFailureAlsoPropagates() {
        val failure = IllegalStateException("injected relation query failure")
        val failingRepository = object : RecordRepository by repository {
            override fun children(parentId: String): List<UniversalRecord> = throw failure
        }
        val error = assertThrows(IllegalStateException::class.java) {
            RecordPersistence(failingRepository).persist(tweet("p"))
        }
        assertSame(failure, error)
    }

    @Test fun doubanGroupTopicRexxarAndCommentsDoNotEraseNativeStatistics() {
        val url = "https://frodo.douban.com/api/v2/group/topic/505438840"
        val id = "douban_group_topic_505438840"
        fun capture(query: String, json: String) = DoubanGroupTopicParser.parse(
            RawData(url = "$url?$query", json = json, hostApp = "com.douban.frodo"), url
        ).single()
        persistence.persist(capture("source=feed", """{
            "id":"505438840","title":"帖子","content":"正文",
            "group":{"id":"720659","name":"小组"},
            "like_count":6,"collections_count":8,"comments_count":254
        }"""))
        val rexxar = capture("s=rexxar_new", """{
            "id":"505438840","title":"帖子","content":"正文",
            "like_count":0,"comments_count":255
        }""")
        persistence.persist(rexxar)
        val commentUrl = "$url/comments"
        DoubanGroupTopicCommentsParser.parse(
            RawData(url = commentUrl, json = """{"comments":[{"id":"c","text":"回复","vote_count":2}]}"""),
            commentUrl
        ).forEach { persistence.persist(it) }
        val stored = meta(repository.find(id)!!)
        assertEquals(6L, stored.get("like_count").asLong)
        assertEquals(6L, stored.get("voteup_count").asLong)
        assertEquals(8L, stored.get("favlists_count").asLong)
        assertEquals(255L, stored.get("comment_count").asLong)
        assertEquals("小组", stored.get("group_name").asString)
        assertNotNull(repository.find("douban_comment_c"))

        // Real native decreases and zeroes are updates, not missing values.
        for (count in listOf(3L, 0L)) {
            persistence.persist(capture("source=feed", """{
                "id":"505438840","like_count":$count,"collections_count":$count,"comments_count":$count
            }"""))
            val updated = meta(repository.find(id)!!)
            for (key in listOf("like_count", "voteup_count", "favlists_count", "comment_count")) {
                assertEquals(key, count, updated.get(key).asLong)
            }
        }
    }

    @Test fun doubanLegacyBackfillFailureStillWarmsTitleWithoutRepairingEarlierMetadata() {
        val context = RecordPersistenceContext()
        val writer = RecordPersistence(repository, context)
        writer.persist(interest())
        repository.failOnWrite = repository.writes.size + 2
        // Unlike X, this task deliberately preserves Douban's historical best-effort backfill.
        writer.persist(subject("失败回填标题"))
        val early = repository.find("douban_interest_i")!!
        assertEquals("电影短评：987654321", early.title)
        assertFalse(meta(early).has("subject_title"))
        repository.failOnWrite = null
        repository.rows.clear() // Simulate caller rollback; completion state remains process-owned.
        val next = interest("retry", type = "tv")
        RecordPersistence(repository, context).persist(next)
        assertEquals("剧集短评：失败回填标题", next.title)
        assertEquals("失败回填标题", meta(next)["subject_title"].asString)
    }

    @Test fun completionEvidenceRoutesThroughInterfaceWithoutConcretePlatformCast() {
        var completed = false
        val evidence = object : CompletionEvidence { override val platform = "custom" }
        val collaborator = object : PlatformPersistence {
            override fun completeEvidence(evidence: CompletionEvidence, repository: RecordRepository) {
                completed = true
            }
        }
        RecordPersistence(repository, platforms = mapOf("custom" to collaborator)).complete(evidence)
        assertTrue(completed)
    }

    @Test fun doubanEmptyCaptureEvidenceWarmsCacheWithoutWritesOrRepeatedItemLookups() {
        repository.put(subject("捕获前数据库标题"))
        val context = RecordPersistenceContext()
        val writer = RecordPersistence(repository, context)
        val evidence = me.melon.record.parser.douban.DoubanInterestsResult(
            emptyList(), me.melon.record.parser.douban.DoubanTitleLookup("987654321", "movie")
        )
        writer.complete(evidence.completionEvidence())
        assertEquals(listOf("douban_movie_987654321"), repository.reads)
        repository.rows.clear() // The warmed title must survive loss/rollback of database rows.
        val next = interest("next", type = "tv")
        RecordPersistence(repository, context).completeChunk(listOf(next))
        assertEquals("剧集短评：捕获前数据库标题", next.title)
        assertEquals(1, repository.reads.size)
        assertEquals(1, repository.writes.size)
    }

    @Test fun doubanSubjectCompletionRetainsCaptureOrderBeforeAnyWrites() {
        val before = interest("before")
        val after = interest("after")
        persistence.completeChunk(listOf(before, subject("中间标题"), after))
        assertFalse(meta(before).has("subject_title"))
        assertEquals("中间标题", meta(after)["subject_title"].asString)
        assertTrue(repository.rows.isEmpty())
        listOf(before, subject("中间标题"), after).forEach(persistence::persistCompleted)
        assertEquals("电影短评：中间标题", repository.find(before.platformId)!!.title)
        assertFalse(meta(repository.find(before.platformId)!!).has("subject_title"))
    }

    @Test fun answerIdIsNotProofOfAnExistingQuestion() {
        persistence.persist(answer())
        assertEquals("", repository.find("zhihu_answer_a")!!.parentId)
        assertNull(repository.find("zhihu_question_q"))
        persistence.persist(question())
        assertEquals("zhihu_question_q", repository.find("zhihu_answer_a")!!.parentId)
        assertEquals(listOf("zhihu_answer_a", "zhihu_question_q", "zhihu_answer_a"), repository.writes)
    }

    @Test fun answerBindsToPersistedQuestionAndPreservesExistingNonblankParent() {
        persistence.persist(question())
        persistence.persist(answer())
        persistence.persist(answer("b", parent = "explicit_parent"))
        assertEquals("zhihu_question_q", repository.find("zhihu_answer_a")!!.parentId)
        assertEquals("explicit_parent", repository.find("zhihu_answer_b")!!.parentId)
    }

    @Test fun questionRefsLinkOnlyExistingUnboundAnswersWithoutCreatingPlaceholders() {
        persistence.persist(answer("a", qid = "other"))
        persistence.persist(answer("b", parent = "existing_parent"))
        persistence.persist(question(refs = """[{"id":"a"},{"id":"b"},{"id":"missing"}]"""))
        assertEquals("zhihu_question_q", repository.find("zhihu_answer_a")!!.parentId)
        assertEquals("existing_parent", repository.find("zhihu_answer_b")!!.parentId)
        assertNull(repository.find("zhihu_answer_missing"))
    }

    @Test fun doubanLateSubjectBackfillsOnlyTitleAndLaterInterestGetsMetadata() {
        persistence.persist(interest())
        persistence.persist(subject())
        val early = repository.find("douban_interest_i")!!
        assertEquals("电影短评：标题", early.title)
        assertFalse(meta(early).has("subject_title"))
        persistence.persist(interest("later"))
        val later = repository.find("douban_interest_later")!!
        assertEquals("电影短评：标题", later.title)
        assertEquals("标题", meta(later)["subject_title"].asString)
        assertEquals(listOf("douban_interest_i", "douban_movie_987654321", "douban_interest_i", "douban_interest_later"), repository.writes)
    }

    @Test fun doubanReviewTitleRetainsLegacyIdOnlyReuseButContextsRemainIsolated() {
        persistence.persist(interest())
        persistence.persist(UniversalRecord(
            platform = "douban", recordType = "review", platformId = "review", parentId = "douban_movie_987654321",
            extraMetadata = """{"subject_id":"987654321","subject_type":"movie","subject_title":"来自长评"}"""
        ))
        assertEquals("电影短评：来自长评", repository.find("douban_interest_i")!!.title)
        val nextChunk = interest("later")
        persistence.completeChunk(listOf(nextChunk))
        persistence.persistCompleted(nextChunk)
        assertEquals("来自长评", meta(repository.find("douban_interest_later")!!)["subject_title"].asString)
        persistence.persist(interest("tv", type = "tv"))
        assertEquals("来自长评", meta(repository.find("douban_interest_tv")!!)["subject_title"].asString)
        val separate = MemoryRecordRepository()
        RecordPersistence(separate).persist(interest())
        assertEquals("电影短评：987654321", separate.find("douban_interest_i")!!.title)
    }

    @Test fun doubanWarmContextSurvivesSeparateRunsButColdContextKeepsLegacyFirstChildMiss() {
        val context = RecordPersistenceContext()
        val runOne = RecordPersistence(repository, context)
        runOne.persist(interest())
        runOne.persist(UniversalRecord(
            platform = "douban", recordType = "review", platformId = "review", parentId = "douban_movie_987654321",
            extraMetadata = """{"subject_id":"987654321","subject_type":"movie","subject_title":"长评标题"}"""
        ))
        assertFalse(meta(repository.find("douban_interest_i")!!).has("subject_title"))

        val runTwo = RecordPersistence(repository, context)
        val nextChunk = interest("warm")
        runTwo.completeChunk(listOf(nextChunk))
        runTwo.persistCompleted(nextChunk)
        assertEquals("长评标题", meta(nextChunk)["subject_title"].asString)

        // Restarting the process used to lose the old global cache too. Do not silently replace
        // its first-child-only cold lookup with a scan of later reviews or title-only backfills.
        val cold = interest("cold")
        RecordPersistence(repository, RecordPersistenceContext()).persist(cold)
        assertEquals("电影短评：987654321", cold.title)
        assertFalse(meta(cold).has("subject_title"))
    }

    @Test fun doubanWarmLatestReviewOverridesStoredSubjectAcrossRunsButColdLookupDoesNot() {
        val context = RecordPersistenceContext()
        val firstRun = RecordPersistence(repository, context)
        firstRun.persist(subject("旧条目标题"))
        firstRun.persist(UniversalRecord(
            platform = "douban", recordType = "review", platformId = "review", parentId = "douban_movie_987654321",
            extraMetadata = """{"subject_id":"987654321","subject_type":"movie","subject_title":"最新长评标题"}"""
        ))
        val warm = interest("warm")
        RecordPersistence(repository, context).persist(warm)
        assertEquals("电影短评：最新长评标题", warm.title)
        val cold = interest("cold")
        RecordPersistence(repository).persist(cold)
        assertEquals("电影短评：旧条目标题", cold.title)
    }

    @Test fun doubanSampleTitlePrecedesDatabaseUntilCaptureOverridesItAndColdStartResetsIt() {
        val sampleSubject = subject("数据库标题").apply {
            platformId = "douban_movie_1292722"
            extraMetadata = """{"subject_id":"1292722","subject_type":"movie","subject_title":"数据库标题"}"""
        }
        repository.put(sampleSubject)
        val context = RecordPersistenceContext()
        val first = interest("sample", subject = "1292722")
        RecordPersistence(repository, context).persist(first)
        assertEquals("电影短评：泰坦尼克号", first.title)

        RecordPersistence(repository, context).persist(sampleSubject.copy(title = "捕获标题",
            extraMetadata = """{"subject_id":"1292722","subject_type":"movie","subject_title":"捕获标题"}"""))
        val warm = interest("warm", subject = "1292722")
        RecordPersistence(repository, context).persist(warm)
        assertEquals("电影短评：捕获标题", warm.title)
        val cold = interest("cold", subject = "1292722")
        RecordPersistence(repository).persist(cold)
        assertEquals("电影短评：泰坦尼克号", cold.title)
    }

    @Test fun doubanDatabaseHitWarmsIdOnlyContextBeforeNextRecordInSameChunk() {
        repository.put(subject("电影标题"))
        repository.put(subject("剧集标题", type = "tv"))
        val movie = interest("movie")
        val tv = interest("tv", type = "tv")
        persistence.completeChunk(listOf(movie, tv))
        assertEquals("电影标题", meta(movie)["subject_title"].asString)
        assertEquals("电影标题", meta(tv)["subject_title"].asString)
    }

    @Test fun doubanCompletionContextLikeLegacyCacheIsNotRolledBackWithDatabase() {
        val context = RecordPersistenceContext()
        val failedRun = RecordPersistence(repository, context)
        failedRun.completeChunk(listOf(subject("未提交标题")))
        assertTrue(repository.rows.isEmpty())
        // The original parser had already registered the subject before the write transaction.
        val retry = interest("retry")
        RecordPersistence(repository, context).persist(retry)
        assertEquals("电影短评：未提交标题", retry.title)
    }

    @Test fun doubanLookupStillUsesOnlyFirstChildMetadata() {
        repository.put(interest())
        repository.put(UniversalRecord(
            platform = "douban", platformId = "review", parentId = "douban_movie_987654321",
            extraMetadata = """{"subject_title":"不能越过首条记录"}"""
        ))
        persistence.persist(interest("new"))
        assertFalse(meta(repository.find("douban_interest_new")!!).has("subject_title"))
    }

    @Test fun repeatedRecordsMergeInArrivalOrderAndKeepIdentityAndGenericFallbacks() {
        repository.put(UniversalRecord(
            id = 42, platform = "unknown", platformId = "same", title = "old title", content = "old body",
            parentId = "parent", authorId = "author", authorName = "name", authorAvatar = "avatar",
            sourceUrl = "url", publishTime = 20, captureTime = 30
        ))
        persistence.persist(UniversalRecord(platform = "unknown", platformId = "same", publishTime = 10, captureTime = 40))
        val result = repository.find("same")!!
        assertEquals(42L, result.id)
        assertEquals("old body", result.content)
        assertEquals("old title", result.title)
        assertEquals("parent", result.parentId)
        assertEquals("author", result.authorId)
        assertEquals("name", result.authorName)
        assertEquals("avatar", result.authorAvatar)
        assertEquals("url", result.sourceUrl)
        assertEquals(20L, result.publishTime)
        assertEquals(40L, result.captureTime)
    }

    @Test fun platformHooksRunAroundGenericMergeAndAfterWrite() {
        val events = mutableListOf<String>()
        repository.put(UniversalRecord(id = 9, platform = "custom", platformId = "r", title = "old title", content = "old body"))
        val policy = object : PlatformPersistence {
            override fun complete(record: UniversalRecord, repository: RecordRepository) { events.add("complete") }
            override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) {
                assertEquals(9L, incoming.id)
                assertEquals("", incoming.title)
                events.add("body")
            }
            override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
                assertEquals("old title", incoming.title)
                assertEquals("old body", incoming.content)
                events.add("extra")
            }
            override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
                assertEquals("old body", repository.find("r")!!.content)
                events.add("after")
            }
        }
        RecordPersistence(repository, platforms = mapOf("custom" to policy)).persist(UniversalRecord(platform = "custom", platformId = "r"))
        assertEquals(listOf("complete", "body", "extra", "after"), events)
    }

    @Test fun redditExplicitlyClearedCompleteMarkdownIsNotRestoredByGenericFallback() {
        fun comment(body: String, captured: Long) = UniversalRecord(
            platform = "reddit", recordType = "comment", platformId = "reddit_comment_a", content = body,
            captureTime = captured,
            extraMetadata = """{"body_source":"markdown","body_complete":true,"body_length":${body.length}}"""
        )
        persistence.persist(comment("old body", 100))
        persistence.persist(comment("", 200))
        assertEquals("", repository.find("reddit_comment_a")!!.content)
        assertTrue(meta(repository.find("reddit_comment_a")!!)["body_complete"].asBoolean)
    }

    @Test fun weiboBodyCompletenessAndThreadsReplyMergeAreAppliedBeforeGenericFallbacks() {
        persistence.persist(UniversalRecord(platform = "weibo", platformId = "weibo_a", content = "完整正文",
            extraMetadata = """{"full_text":true,"like_count":9}"""))
        persistence.persist(UniversalRecord(platform = "weibo", platformId = "weibo_a", content = "截断",
            extraMetadata = """{"like_count":0}"""))
        val weibo = repository.find("weibo_a")!!
        assertEquals("完整正文", weibo.content)
        assertTrue(meta(weibo)["full_text"].asBoolean)
        assertEquals(0, meta(weibo)["like_count"].asInt)
        persistence.persist(UniversalRecord(platform = "threads", platformId = "threads_a", recordType = "comment",
            content = "较长正文", parentId = "threads_parent"))
        persistence.persist(UniversalRecord(platform = "threads", platformId = "threads_a", recordType = "thread", content = "短"))
        val threads = repository.find("threads_a")!!
        assertEquals("comment", threads.recordType)
        assertEquals("threads_parent", threads.parentId)
        assertEquals("较长正文", threads.content)
    }

    @Test fun subjectPreviewDoesNotOverwriteRealBodyAndMetadataStillMerges() {
        val old = subject().apply { content = "完整简介"; extraMetadata = """{"rating":8,"old":"kept"}""" }
        persistence.persist(old)
        val incoming = subject().apply { content = title; extraMetadata = """{"rating":0,"new":"added"}""" }
        persistence.persist(incoming)
        assertEquals("完整简介", incoming.content)
        assertEquals(8, meta(incoming)["rating"].asInt)
        assertEquals("kept", meta(incoming)["old"].asString)
        assertEquals("added", meta(incoming)["new"].asString)
    }

    @Test fun v2AndHtmlParsersEmitQuestionEvidenceWithoutDatabaseBinding() {
        val item = JsonParser.parseString("""{"id":"a","type":"answer","question":{"id":"q","title":"问题"},"excerpt":"正文"}""").asJsonObject
        val raw = RawData(url = "https://api.zhihu.com/answers/v2/a", json = item.toString(), hostApp = "com.zhihu.android")
        val v2 = ZhihuItemV2Parser.parse(item, raw)!!
        val htmlUrl = "https://www.zhihu.com/appview/v2/answer/a"
        val html = """<script id="js-initialData" type="text/json">{"initialState":{"answers":{"a":{"question":{"id":"q","title":"问题"},"content":"正文"}}}}</script>"""
        val v1 = ZhihuAnswerParser.parse(raw.copy(url = htmlUrl, json = html), htmlUrl).single()
        for (parsed in listOf(v2, v1)) {
            assertEquals("", parsed.parentId)
            assertEquals("q", meta(parsed)["question_id"].asString)
            persistence.persist(question())
            persistence.persist(parsed)
            assertEquals("zhihu_question_q", repository.find(parsed.platformId)!!.parentId)
        }
    }

    @Test fun doubanSubjectParsingCannotChangeSubsequentInterestParsing() {
        val url = "https://frodo.douban.com/api/v2/movie/987654321/interests"
        val raw = RawData(url = url, json = """{"interests":[{"id":"i","comment":"正文"}]}""", hostApp = "com.douban.frodo", timestamp = 100)
        val before = DoubanHotInterestsParser.parse(raw, url).single()
        DoubanSubjectParser.parseSubjectFromObject(
            JsonParser.parseString("""{"id":"987654321","title":"不应泄露","subtype":"movie"}""").asJsonObject, raw
        )
        val after = DoubanHotInterestsParser.parse(raw, url).single()
        assertEquals(before.title, after.title)
        assertEquals(before.extraMetadata, after.extraMetadata)
        assertEquals("电影短评：987654321", after.title)
    }
}
