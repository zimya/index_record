# Threads 抓取与解析调查

## 实测环境与结论

- 包名：`com.instagram.barcelona`；设备 APK Manifest 版本 `454.0.0.54.79`（versionCode `512408646`）。
- Manifest 声明 `INTERNET` 及 `@xml/fb_network_security_config`。Manifest 本身不能判断业务接口。
- APK 反编译确认 `com.facebook.tigon.TigonXplatService.sendRequest` 两个重载，以及 `TigonRequest.url()`、`TigonCallbacks.onBody(ByteBuffer)` / `onEOM` / `onError`。TigonCallbacks 是类，不能用接口代理替换。
- 真机证明业务请求使用 `https://i.instagram.com`，通过 Tigon Java 响应回调可取得明文，不需要绕过 SSL 校验、不需要中间人证书。并非“Threads 不使用 HTTPS”。
- `ThreadsHook.java` 在原回调前复制 ByteBuffer（不移动原 buffer 的 position），响应结束后异步落入宿主 `cache/index_record_queue/*.bin` / `*.meta`，复用 Binder / Provider 同步。
- 观察于 2026-11-12。这里只对该版本、实际浏览路径作结论，不保证其他版本或未触发的原生 Pando 路径。

## 哪些响应值得记录

| 入口 / 数据结构 | 实测情况 | 处理 |
| --- | --- | --- |
| `/api/v1/feed/text_post_app_timeline/` | 首页，NDJSON，多段 `feed_items → text_post_app_thread → thread_items → post` | 记录非广告帖子，按稳定 ID 跨 chunk 去重 |
| `/api/v1/text_feed/<post_id>/replies_streaming/` | 详情及翻页，NDJSON；`containing_thread`、`reply_threads` | 记录主帖与回复，保留直接父节点 |
| 缓存 `threads → thread_items → post` | 主帖、一级回复、二级回复 | 已真实回放验证 |
| `/media/`、GraphQL 的明确 `data/threads/medias/media` 容器 | Hook 和解析器预留结构支持，非空 media/GraphQL 包装主要是合成测试 | 不泛化遍历任意 GraphQL edges，不宣称所有查询都覆盖 |
| `injected.ad_id`、`ad_action` | 真实首页中的广告标记 | 在祖先容器处停止解析，避免广告内 post 漏入 |
| `suggested_users` | 真实首页中的账户推荐单元 | 不记录其卡片或预取帖子 |
| `new_stories` / `old_stories` / `priority_stories` | 通知，可能内嵌 post | Hook 排除 notifications 路径；解析器也排除通知容器 |
| `user`、`users`、推荐账户、空 `medias/threads` | 宿主 HTTP 缓存中大量出现 | 不产生记录 |
| 心跳、日志、图片、视频二进制 | 不属于关注内容 | URL / 内容结构过滤；不把媒体下载响应存为帖子 |
| `related_threads`、任意预取字段 | 不等于已浏览的回复 | 不遍历 |

URL 只作第一层候选过滤。混合首页响应仍可能含广告，最终逐项过滤在解析器执行；并非丢弃整个混合响应。没有广告真实样本覆盖的其他广告标记由合成测试保护。

## 记录模型与元数据

- `platform = threads`，`sourceApp = com.instagram.barcelona`。
- 主帖 `recordType = thread`，回复 `recordType = comment`；两者共用 `threads_post_<数字 pk>`，去掉 `_authorId` 后缀。大整数 ID 不经过浮点转换。
- 回复直接父节点来自 `text_post_app_info.reply_to_post_id`。回复的回复仍是 post，而不是 caption 内的 comment 对象；caption 只取正文，不单独落库。
- 记录作者 ID、昵称、用户名、头像、发布时间、采集时间、原文链接、正文及图片/轮播/视频链接；引用或转发附件内嵌正文，不额外制造未浏览记录。
- 计数：`like_count`、`reply_count`（缺失时读 `direct_reply_count`）、`repost_count`、`quote_count`、`reshare_count`；`view_count` 有结构支持，但当前抓获样本未提供，不能把界面显示的浏览数凭空补入。
- `repost_count`（转发）、`quote_count`（引用）、`reshare_count`（分享）分别保留，不混算。实测详情主帖：点赞 22、回复 10、转发 2、分享 4。
- 同一帖子跨接口更新保留已知正文、作者、父链和缺失元数据，显式零计数可更新。不创建占位父帖；父内容抓到后通过同一稳定 ID 自动关联。

## 可追溯验证

原始本地材料（被 `.gitignore` 忽略，可能含隐私）：

- `base.apk`、`resources/resources/AndroidManifest.xml`、`TigonXplatService.java`、`TigonCallbacks.java`、`TigonRequest.java`：静态证据。
- `http_responses.tar` 及解压 JSON：初始宿主私有 HTTP 缓存。
- `live-queue.tar`：真实详情回复抓取；`live-feed-queue.tar`：真实首页抓取。
- `1794502041440_47209997275061.bin`：4 chunks，去重为 1 主帖 + 4 回复。
- `1794502042068_47210625315113.bin`：2 chunks，5 回复。
- 三份 `17945023*.bin` 首页：分别 10/6/5 chunks，每份保留 5 帖、排除 1 广告；其中第二份还排除 1 推荐账户单元，合并 13 唯一帖子。
- 初始缓存 `402a47a0-body_gzip.clean.json`：4 条一级回复 + 各自二级回复，共 8 条，父链已回放验证。
- `record-verification.json`：通过 App 自带 debug ObjectBox Browser 只读查询验证，33 条记录（24 主帖、9 回复），含原始测试主帖计数及 9 条直接父链。随后浏览产生的新记录可能使数据库数量继续增长。
- `record-home.png`：记录 App 首页真实 Threads 卡片截图。LSPosed 已通过管理器为记录模块勾选 Threads 作用域。

可提交的回归样本在 `app/src/test/resources/threads`，均为保留必要结构的精简脱敏 fixture，不提交 APK、用户数据库、原始响应、模块配置或日志。

## 边界

- 只记录宿主实际返回的数据；没有自动请求全量历史回复，未加载的分页不会补抓。
- 当前 UI 复用通用详情与评论树。详情可以展示已采集嵌套回复，首页默认隐藏评论（沿用现有行为）。
- 不支持任意未知 GraphQL edges、所有富文本/新附件类型或端到端加密私信；这些不属于本次已验证范围。
- 坏 NDJSON 按行边界恢复；不保证恢复同一行内损坏 JSON 后面的对象。
- 稀疏响应合并偏向保留更完整正文，不进行主动删短正文的同步。
