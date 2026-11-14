package com.melonhu.indexrecord.data.repository

import com.google.gson.JsonObject
import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordCommentSelectionTest {
    private val post = UniversalRecord(platform = "reddit", recordType = "post", platformId = "reddit_post_abcd")

    private fun commentId(id: String): String = "reddit_comment_$id"

    private fun comment(
        id: String,
        parentId: String = post.platformId,
        score: Long? = null,
        time: Long = 0,
        postId: String? = post.platformId,
        hidden: Boolean = false
    ): UniversalRecord {
        val metadata = JsonObject().apply {
            score?.let { addProperty("score", it) }
            postId?.let { addProperty("post_id", it) }
            if (hidden) addProperty("is_score_hidden", true)
        }
        return UniversalRecord(
            platform = "reddit", recordType = "comment", platformId = commentId(id), parentId = parentId,
            publishTime = time, content = "**$id**", extraMetadata = metadata.toString()
        )
    }


    @Test
    fun missingParentBranchesContainOnlyRealRepliesForTheCurrentPost() {
        val connected = comment("a", score = 0)
        val orphan = comment("x", commentId("missing"), score = 10)
        val child = comment("y", orphan.platformId, score = 7)
        val foreign = comment("z", "reddit_post_other", postId = "reddit_post_other")
        val rows = selectRedditComments(post, listOf(child, foreign, orphan, connected))
        assertEquals(listOf(child, orphan, connected), rows)
        assertEquals(commentId("missing"), orphan.parentId)
        assertEquals(orphan.platformId, child.parentId)
    }

    @Test
    fun capturedAncestorsWithoutPostMetadataAreNotFalselyMarkedMissing() {
        val parent = comment("b", commentId("missing"), postId = null)
        val child = comment("c", parent.platformId)
        val rows = selectRedditComments(post, listOf(child, parent))
        assertEquals(listOf(child, parent), rows)
    }

    @Test
    fun directParentLinksWorkWithoutRootMetadataAndUnidentifiedOrphansAreNotGuessed() {
        val parent = comment("a", postId = null)
        val child = comment("b", parent.platformId, postId = null)
        val unidentified = comment("c", commentId("missing"), postId = null).apply {
            extraMetadata = """{"child_count":100,"comment_count":200}"""
        }
        val rows = selectRedditComments(post, listOf(child, unidentified, parent))
        assertEquals(listOf(child, parent), rows)
    }

    @Test
    fun replyDetailsShowOnlyTheirOwnDescendants() {
        val current = comment("a")
        val child = comment("b", current.platformId)
        val grandchild = comment("c", child.platformId)
        val sibling = comment("d")
        val unrelatedOrphan = comment("x", commentId("missing"))
        val rows = selectRedditComments(current,
            listOf(current, child, grandchild, sibling, unrelatedOrphan)
        )
        assertEquals(listOf(child, grandchild), rows)
    }
}
