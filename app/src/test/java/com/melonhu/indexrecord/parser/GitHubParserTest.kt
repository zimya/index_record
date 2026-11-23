package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.hook.GitHubHook
import com.melonhu.indexrecord.parser.github.GitHubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubParserTest {

    private val issueUrl = "https://api.github.com/repos/octocat/Hello-World/issues/1"

    @Test
    fun issueJsonBecomesMarkdownRecord() {
        val json = """
            {
              "id": 872858,
              "number": 1,
              "title": "Edited README via GitHub",
              "body": "Hello **world**",
              "state": "closed",
              "user": {"login": "octocat", "avatar_url": "https://avatars.githubusercontent.com/u/583231?v=4"},
              "html_url": "https://github.com/octocat/Hello-World/issues/1",
              "created_at": "2011-01-01T00:00:00Z",
              "comments": 3,
              "labels": [{"name": "bug"}]
            }
        """.trimIndent()
        val records = GitHubParser.parseResult(RawData(url = issueUrl, json = json), issueUrl).records

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("github", record.platform)
        assertEquals("issue", record.recordType)
        assertEquals("github_octocat_Hello-World_issue_1", record.platformId)
        assertEquals("Edited README via GitHub", record.title)
        assertEquals("Hello **world**", record.content)
        assertEquals("markdown", record.contentFormat)
        assertEquals("octocat", record.authorName)
        assertTrue(record.extraMetadata.contains("bug"))
    }

    @Test
    fun pullRequestJsonUsesPrType() {
        val json = """
            {
              "number": 7,
              "title": "Add guide",
              "body": "docs",
              "state": "open",
              "merged": false,
              "pull_request": {"url": "https://api.github.com/repos/octocat/Hello-World/pulls/7"},
              "user": {"login": "hubot"},
              "html_url": "https://github.com/octocat/Hello-World/pull/7",
              "base": {"repo": {"full_name": "octocat/Hello-World"}},
              "created_at": "2024-05-01T10:00:00Z"
            }
        """.trimIndent()
        val url = "https://api.github.com/repos/octocat/Hello-World/pulls/7"
        val record = GitHubParser.parseResult(RawData(url = url, json = json), url).records.single()

        assertEquals("pr", record.recordType)
        assertEquals("github_octocat_Hello-World_pr_7", record.platformId)
    }

    @Test
    fun commentArrayKeepsIssueParent() {
        val json = """
            [
              {
                "id": 99001,
                "body": "Same here",
                "user": {"login": "reviewer"},
                "html_url": "https://github.com/octocat/Hello-World/issues/1#issuecomment-99001",
                "created_at": "2024-05-02T08:00:00Z"
              }
            ]
        """.trimIndent()
        val url = "https://api.github.com/repos/octocat/Hello-World/issues/1/comments"
        val record = GitHubParser.parseResult(RawData(url = url, json = json), url).records.single()

        assertEquals("comment", record.recordType)
        assertEquals("github_comment_99001", record.platformId)
        assertEquals("github_octocat_Hello-World_issue_1", record.parentId)
    }

    @Test
    fun hookOnlyAcceptsRepositoryContentEndpoints() {
        assertTrue(GitHubHook.isTargetUrl(issueUrl))
        assertTrue(GitHubHook.isTargetUrl("https://api.github.com/repos/o/r/pulls/2"))
        assertTrue(GitHubHook.isTargetUrl("https://api.github.com/graphql"))
        assertFalse(GitHubHook.isTargetUrl("https://api.github.com/user"))
        assertFalse(GitHubHook.isTargetUrl("https://example.com/repos/o/r/issues/1"))
        assertFalse(GitHubHook.isTargetUrl(null))
    }
}
