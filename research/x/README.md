# X Android 测试响应

这些 JSON 直接提取自连接手机的 `/data/user/0/com.twitter.android/cache/index_record_queue/*.bin`，不是浏览器响应，也未按旧测试伪造内容。采集过程仅浏览首页、推文详情、书签、喜欢列表和 Kotlin 搜索结果，没有点赞、发帖或修改书签。

`captures.json` 记录每份响应对应的原始 bin 文件名、采集时间戳、宿主包名、去除查询参数的真实 URL 和原始字节 SHA-256。JSON 保留原始 bin 内容；未保存请求头、Cookie 或认证信息。样本含公开帖子及当前账号书签/喜欢列表，请在对外分发前确认隐私范围。

| 文件 | 内容 |
| --- | --- |
| `graphql_HomeTimeline.json` | 首页，28 个非广告时间线条目及 7 个广告条目 |
| `graphql_ConversationTimeline.json` | Android 详情，主帖、引用内容、两级回复和 promoted conversationthread |
| `graphql_SearchTimelineQuery.json` | Kotlin 搜索结果 |
| `graphql_BookmarksTimelineQuery.json` | 书签列表 |
| `graphql_UserProfileFavoritesTimelineQuery.json` | 喜欢列表（可包含回复） |

详情样本没有旧版 `legacy.conversation_id_str`。现有解析器对二级回复的 `parentId` 暂用 `twitter_tweet_` 前缀，入库后由 `XRelations` 校正为已存在的评论 ID。`XParserTest` 检查解析阶段的父 ID 与正文，不把该测试当作 ObjectBox 入库关系校正验证。

运行全量单元测试：

```sh
./gradlew test --continue --rerun-tasks --console=plain
```
