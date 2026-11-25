package com.melonhu.indexrecord.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliCommentsTest {
    @Test
    fun mainListKeepsOnlyItsTopicAndDoesNotDuplicatePinnedReplies() {
        val root = Reply(1, children = listOf(Reply(2, parent = 1, root = 1)))
        val json = BilibiliComments.project(Page(listOf(root, Reply(3, type = 1)), listOf(root)), false)
            ?: error("Missing topic")
        assertEquals(11L, json.getLong("topic_type"))
        assertEquals(123L, json.getLong("topic_id"))
        val rows = json.getJSONArray("replies")
        assertEquals(2, rows.length())
        assertEquals(1L, rows.getJSONObject(1).getLong("parent"))
        assertEquals(1L, rows.getJSONObject(1).getLong("root"))
        assertEquals(0L, rows.getJSONObject(1).getLong("like"))
        assertEquals("示例正文", rows.getJSONObject(1).getString("text"))
    }

    @Test
    fun detailListProjectsRootAndNestedReplies() {
        val root = Reply(1, children = listOf(Reply(2, parent = 1, root = 1)))
        val rows = requireNotNull(BilibiliComments.project(Detail(root), true)).getJSONArray("replies")
        assertEquals(listOf(1L, 2L), (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") })
    }

    @Test
    fun avatarFallsBackToMemberV2Basic() {
        val replies = listOf(
            Reply(1, member = Member("https://i0.hdslb.com/primary.jpg"), memberV2 = MemberV2(Member("https://i0.hdslb.com/basic.jpg"))),
            Reply(2, member = Member(""), memberV2 = MemberV2(Member("//i1.hdslb.com/basic.jpg"))),
            Reply(3)
        )
        val rows = requireNotNull(BilibiliComments.project(Page(replies, emptyList()), false)).getJSONArray("replies")
        assertEquals("https://i0.hdslb.com/primary.jpg", rows.getJSONObject(0).getString("author_avatar"))
        assertEquals("//i1.hdslb.com/basic.jpg", rows.getJSONObject(1).getString("author_avatar"))
        assertEquals(false, rows.getJSONObject(2).has("author_avatar"))
    }

    @Test
    fun emptyResponseDoesNotInventTopic() {
        assertNull(BilibiliComments.project(Page(emptyList(), emptyList()), false))
    }

    class Page(val replies: List<Reply>, val topReplies: List<Reply>)
    class Detail(val root: Reply)
    class Content(val message: String = "示例正文")
    class Member(val face: String)
    class MemberV2(val basic: Member)
    class Reply(
        val id: Long,
        val type: Long = 11,
        val parent: Long = 0,
        val root: Long = 0,
        children: List<Reply> = emptyList(),
        val member: Member? = null,
        val memberV2: MemberV2? = null
    ) {
        val oid = 123L
        val like = 0L
        val content = Content()
        val replies = children
    }
}
