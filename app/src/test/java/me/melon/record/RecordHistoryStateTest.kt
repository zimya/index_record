package me.melon.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.repository.HistoricalRecord
import me.melon.record.logic.DiffKind
import me.melon.record.presentation.historyMetadataChanges
import me.melon.record.presentation.historyPublishTime
import java.time.ZoneId
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.viewmodel.chronologicalPair
import me.melon.record.viewmodel.historyDiffSections
import me.melon.record.viewmodel.recordHistoryStates
import org.junit.Assert.*
import org.junit.Test

class RecordHistoryStateTest {
    private fun entry(key: String, time: Long, legacy: Boolean = false) =
        HistoricalRecord(key, DetailRecord(content = "相同正文", captureTime = time), legacy)

    @Test fun identicalCapturesAndLegacyAreNotDeduplicated() = runBlocking {
        val entries = listOf(entry("new", 30), entry("old", 20), entry("legacy", 10, true))
        val state = recordHistoryStates(flowOf(Result.success(entries))).toList().single()
        assertEquals(entries, state.entries)
        assertTrue(state.entries.last().legacy)
        assertEquals(DetailLoadState.Ready, state.loadState)
        assertTrue(historyDiffSections(entries[0].record, entries[1].record).isEmpty())
    }

    @Test fun failureRetainsSnapshotsAndRecoveryReplacesList() = runBlocking {
        val old = listOf(entry("old", 10))
        val new = listOf(entry("new", 20), old.single())
        val states = recordHistoryStates(flowOf(
            Result.success(old), Result.failure(IllegalStateException("read failed")), Result.success(new)
        )).toList()
        assertEquals(old, states[1].entries)
        assertTrue(states[1].loadState is DetailLoadState.Failed)
        assertEquals(new, states[2].entries)
        assertEquals(DetailLoadState.Ready, states[2].loadState)
    }

    @Test fun directionIsChronologicalRegardlessOfSelectionOrder() {
        val old = entry("old", 10)
        val new = entry("new", 20)
        assertEquals(old to new, chronologicalPair(old, new))
        assertEquals(old to new, chronologicalPair(new, old))
        val sameTime = entry("same", 10)
        assertEquals(sameTime to old, chronologicalPair(sameTime, old))
    }

    @Test fun titleBodyAndFormatChangesAreVisible() {
        val old = DetailRecord(title = "旧题", content = "旧文", contentFormat = "plain_text")
        val new = old.copy(title = "新题", content = "新文", contentFormat = "markdown")
        assertEquals(listOf("标题", "正文", "正文格式"), historyDiffSections(old, new).map { it.title })
        assertEquals("旧文", old.content)
    }

    private val utc = ZoneId.of("UTC")

    @Test fun metadataTitleAndEditTimeAreSeparateReadableDiffs() {
        val old = DetailRecord(platform = "zhihu", extraMetadata = """{"title":"旧标题","updated":1704067200}""")
        val new = old.copy(extraMetadata = """{"updated":1704153600,"title":"新标题"}""")
        val sections = historyDiffSections(old, new)
        assertEquals(listOf("附加信息 · 标题", "附加信息 · 编辑时间"), sections.map { it.title })
        val title = sections.first().diff.parts
        assertEquals("旧标题", title.filter { it.kind != DiffKind.ADDED }.joinToString("") { it.text })
        assertEquals("新标题", title.filter { it.kind != DiffKind.REMOVED }.joinToString("") { it.text })
        val edited = historyMetadataChanges(old.extraMetadata, new.extraMetadata, "zhihu", zone = utc).last()
        assertEquals("2024-01-01 00:00:00 Z", edited.before)
        assertEquals("2024-01-02 00:00:00 Z", edited.after)
        assertFalse(sections.any { it.title == "附加信息" })
    }

    @Test fun metadataTimestampsUseParserUnitsAndSupportIsoOffsets() {
        val seconds = historyMetadataChanges("{}", """{"created":1704067200,"updated_time":1704067200}""", "zhihu", zone = utc)
        val millis = historyMetadataChanges("{}", """{"edited_at":1704067200000}""", "reddit", zone = utc)
        val iso = historyMetadataChanges("{}", """{"edited_at":"2024-01-01T08:00:00+08:00"}""", "reddit", zone = utc)
        assertTrue(seconds.all { it.after == "2024-01-01 00:00:00 Z" })
        assertEquals(seconds.first().after, millis.single().after)
        assertEquals(millis.single().after, iso.single().after)
        // Unit comes from parser semantics, not the magnitude of a recent timestamp.
        assertEquals("1970-01-01 00:00:01 Z", historyMetadataChanges("{}", """{"edited_at":1000}""", "reddit", zone = utc).single().after)
        assertEquals("1970-01-01 00:16:40 Z", historyMetadataChanges("{}", """{"updated":1000}""", "zhihu", zone = utc).single().after)
        assertTrue(historyMetadataChanges("{}", """{"updated":0}""", "zhihu", zone = utc).single().after.startsWith("1970-01-01"))
        assertEquals("2024-01-01 00:00:00 Z", historyPublishTime(1704067200000, utc))
        assertEquals("发布时间未知", historyPublishTime(0, utc))
    }

    @Test fun metadataFieldAdditionsAndRemovalsAreNotWholeJsonDiffs() {
        val changes = historyMetadataChanges("""{"excerpt":"旧摘要","title":"标题"}""", """{"title":"标题","ip_info":"上海"}""", "zhihu")
        assertEquals(listOf("附加信息 · 摘要", "附加信息 · IP 属地"), changes.map { it.title })
        assertEquals("旧摘要", changes[0].before)
        assertEquals("未记录", changes[0].after)
        assertEquals("未记录", changes[1].before)
        assertEquals("上海", changes[1].after)
    }

    @Test fun nestedMetadataUsesNamedPathsWithoutLosingUnknownFields() {
        val changes = historyMetadataChanges(
            """{"poll":{"title":"旧投票","options":{"new_field":1}},"future_key":"旧值"}""",
            """{"poll":{"title":"新投票","options":{"new_field":2}},"future_key":"新值"}""", "reddit"
        )
        assertEquals(listOf("附加信息 · future_key", "附加信息 · 投票 / options / new_field", "附加信息 · 投票 / 标题"), changes.map { it.title })
        assertEquals(listOf("旧值", "1", "旧投票"), changes.map { it.before })
        assertEquals(listOf("新值", "2", "新投票"), changes.map { it.after })
    }

    @Test fun metadataArraysDiffReadableItemsAndNestedObjectFields() {
        val changes = historyMetadataChanges(
            """{"tags":["甲","乙"],"topics":[{"name":"旧话题","id":1}]}""",
            """{"tags":["甲","丙","丁"],"topics":[{"id":1,"name":"新话题"}]}""", "zhihu"
        )
        assertEquals(listOf("附加信息 · 标签 / 第 2 项", "附加信息 · 标签 / 第 3 项", "附加信息 · 话题 / 第 1 项 / 名称"), changes.map { it.title })
        assertEquals(listOf("乙", "未记录", "旧话题"), changes.map { it.before })
        assertEquals(listOf("丙", "丁", "新话题"), changes.map { it.after })
    }

    @Test fun missingIsDistinctFromZeroFalseNullAndEmptyContainers() {
        val changes = historyMetadataChanges("{}", """{"score":0,"is_nsfw":false,"nullable":null,"tags":[],"poll":{},"title":""}""", "reddit")
        assertEquals(6, changes.size)
        assertTrue(changes.all { it.before == "未记录" })
        assertEquals(setOf("0", "否", "空值", "空列表", "空对象", "空文本"), changes.map { it.after }.toSet())
        val removed = historyMetadataChanges("""{"score":0,"is_nsfw":false}""", "{}", "reddit")
        assertEquals(setOf("0", "否"), removed.map { it.before }.toSet())
        assertTrue(removed.all { it.after == "未记录" })
    }

    @Test fun jsonKeyOrderDoesNotCauseChangesIncludingInsideArrays() {
        val first = """{"title":"题","poll":{"name":"投票","id":1},"topics":[{"name":"话题","id":2}]}"""
        val reordered = """{"topics":[{"id":2,"name":"话题"}],"poll":{"id":1,"name":"投票"},"title":"题"}"""
        assertTrue(historyMetadataChanges(first, reordered, "reddit").isEmpty())
        val updated = """{"title":"新题","poll":{"name":"新投票","id":1},"topics":[{"name":"话题","id":2}]}"""
        assertEquals(historyMetadataChanges(first, updated, "reddit"), historyMetadataChanges(reordered, updated, "reddit"))
        val old = DetailRecord(platform = "reddit", extraMetadata = first)
        assertTrue(historyDiffSections(old, old.copy(extraMetadata = reordered)).isEmpty())
    }

    @Test fun malformedMetadataOnlyShowsConciseParseFailureNotRawData() {
        listOf("{broken secret", "{\"title\":", "[1,2]", "null", "{title:'secret'}", "{} trailing").forEach { broken ->
            val change = historyMetadataChanges("{}", broken, "reddit").single()
            assertEquals("附加信息 · 解析状态", change.title)
            assertEquals("未记录", change.before)
            assertEquals("无法解析", change.after)
            val old = DetailRecord(platform = "reddit", extraMetadata = "{}")
            assertEquals("无法解析", historyDiffSections(old, old.copy(extraMetadata = broken)).single().diff.parts
                .filter { it.kind != DiffKind.REMOVED }.joinToString("") { it.text })
        }
        assertTrue(historyMetadataChanges("", "{}", "reddit").isEmpty())
        assertEquals("无法解析", historyMetadataChanges("{broken", "{}", "reddit").single().before)
    }

    @Test fun recordTypeFormatAndPublishTimeUseReadableLabels() {
        val old = DetailRecord(platform = "zhihu", recordType = "answer", contentFormat = "plain_text", publishTime = 1704067200000)
        val new = old.copy(recordType = "article", contentFormat = "markdown", publishTime = 1704153600000)
        val sections = historyDiffSections(old, new)
        assertEquals(listOf("记录类型", "正文格式", "发布时间"), sections.map { it.title })
        val before = sections.map { section -> section.diff.parts.filter { it.kind != DiffKind.ADDED }.joinToString("") { it.text } }
        val after = sections.map { section -> section.diff.parts.filter { it.kind != DiffKind.REMOVED }.joinToString("") { it.text } }
        assertEquals(listOf("回答", "纯文本"), before.take(2))
        assertEquals(listOf("文章", "Markdown"), after.take(2))
        assertEquals(historyPublishTime(old.publishTime), before.last())
        assertEquals(historyPublishTime(new.publishTime), after.last())
        assertFalse(before.last().contains(old.publishTime.toString()))
    }

    @Test fun showAllIncludesUnchangedMetadataWhileDifferencesRemainFilterable() {
        val old = DetailRecord(
            platform = "zhihu", title = "同一标题",
            extraMetadata = """{"title":"元数据标题","updated":1704067200,"voteup_count":0,"tags":["甲"],"poll":{"name":"投票"},"is_preview":false}"""
        )
        val new = old.copy(extraMetadata = old.extraMetadata.replace("\"voteup_count\":0", "\"voteup_count\":1"))
        val sections = historyDiffSections(old, new, includeUnchanged = true)
        val changed = sections.filter { it.hasChanges }
        assertEquals(listOf("附加信息 · 赞同数"), changed.map { it.title })
        assertEquals(historyDiffSections(old, new), changed)
        val unchanged = sections.filterNot { it.hasChanges }
        assertTrue(unchanged.any { it.title == "标题" })
        listOf("附加信息 · 标题", "附加信息 · 编辑时间", "附加信息 · 标签 / 第 1 项", "附加信息 · 投票 / 名称", "附加信息 · 预览内容").forEach { title ->
            val section = unchanged.single { it.title == title }
            assertTrue(section.diff.parts.isNotEmpty())
            assertTrue(section.diff.parts.all { it.kind == DiffKind.UNCHANGED })
        }
    }

    @Test fun identicalMetadataIsAvailableInShowAllIncludingEmptyValuesAndUnknownFields() {
        val record = DetailRecord(
            platform = "reddit",
            extraMetadata = """{"score":0,"is_nsfw":false,"nullable":null,"tags":[],"poll":{},"title":"","future_key":"原值"}"""
        )
        assertTrue(historyDiffSections(record, record).isEmpty())
        val metadata = historyDiffSections(record, record, includeUnchanged = true)
            .filter { it.title.startsWith("附加信息 · ") }
        assertEquals(7, metadata.size)
        assertTrue(metadata.none { it.hasChanges })
        assertEquals(
            setOf("0", "否", "空值", "空列表", "空对象", "空文本", "原值"),
            metadata.map { it.diff.parts.joinToString("") { part -> part.text } }.toSet()
        )
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotAnErrorState() = runBlocking {
        recordHistoryStates(flowOf(Result.failure<List<HistoricalRecord>>(CancellationException("cancel")))).toList()
        Unit
    }
}
