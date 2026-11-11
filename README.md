# 小茵记录 (Index Record)

![Icon](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

> "只要记录下来，就不会被遗忘。"

**小茵记录** 是一个基于 Xposed 框架的 Android 应用，旨在为你自动存档在社交网络上浏览过的内容。

项目名称灵感来源于《魔法禁书目录》中的女主角 **茵蒂克丝 (Index)**，她拥有完全记忆能力（拍照记忆），能够毫无差错地记住 10 万 3000 本魔道书。本应用的目标是成为你移动设备上的 "茵蒂克丝"，帮你记住每一个刷过的瞬间，防止内容被作者删除或由于平台审查而消失。

## 🌟 功能特性

- **无感记录**：基于 OkHttp Hook 技术，在你刷帖时自动在后台捕获原始 JSON/HTML 数据。
- **本地存储**：所有数据均保存在本地 ObjectBox 数据库中，不上传至任何服务器，保护隐私。
- **自动解析**：针对不同平台（目前已支持知乎）进行数据提取，还原标题、作者、正文等核心信息。
- **离线阅读**：即使原帖被删，你依然可以在应用内查看已存下的快照。

## 🛠️ 技术栈

- **Android**: Kotlin, Jetpack Compose, Material 3
- **Hooking**: Xposed Framework
- **Database**: ObjectBox (高性能 NoSQL)
- **Networking**: OkHttp (Hook 目标)
- **Parsing**: Gson, Jsoup

## 🚀 路线图 (TODO)

### 1. 平台支持扩展

- [x] **知乎** (回答、文章)
- [ ] **豆瓣** (书评、影评、广播)
- [x] **百度贴吧** (主帖、楼层、楼中楼；protobuf 原始响应记录)
- [ ] **小红书** (笔记、评论)
- [ ] **Twitter (X)** (推文)
- [ ] **微博** (博文)

### 2. 功能增强

- [ ] **全文搜索**：支持对已记录的内容进行关键词搜索。
- [ ] **图片/视频缓存**：目前仅支持文本，未来考虑增加多媒体资源的本地化存档。
- [ ] **数据导出**：支持导出为 Markdown、PDF 或 JSON 格式。
- [ ] **多端同步**：考虑支持 WebDAV 或其他私有云同步方式。

### 3. UI/UX 优化

- [ ] **内容排版优化**：更好地渲染抓取到的 HTML/Markdown 内容。
- [ ] **分类与标签**：支持按平台、作者或自定义标签对记录进行分类。
- [ ] **暗黑模式**：完美适配系统的深色主题。

## 📦 安装与使用

1. 确保你的设备已安装 Xposed 环境（如 LSPosed）。
2. 安装并激活 **小茵记录** 模块。
3. 在 LSPosed 中勾选目标应用（如知乎）。
4. 打开目标应用浏览内容，回到 **小茵记录** 下拉刷新即可看到存档。

### 百度贴吧

在 LSPosed 作用域中勾选 `com.baidu.tieba`，安装或更新模块后重启贴吧进程。
`TiebaHook.java` 直接 Hook 贴吧响应解码入口和 Wire 的指定消息类型，记录
`PbPage`、新版 `PbList`、`PbFloor` 的原始 protobuf，不依赖 Frida。
响应写入宿主 `cache/index_record_queue/*.bin` 和 `.meta`，成功同步后才删除。

首楼作为主帖，直接回复首楼的帖子作为楼层，只有楼中楼作为评论。详情页可浏览
楼层、展开本地已记录的楼中楼并返回主帖；平台回复数与本地记录数分开显示。
仅保存实际浏览返回的数据，不会后台 POST 补抓；缺失的楼号、时间、头像不编造。
正文与媒体链接会被保留，但不保证未缓存图片可以离线加载。

实机响应字段、脱敏白名单二进制 fixture 和协议证据见
[`app/src/test/resources/tieba/README.md`](app/src/test/resources/tieba/README.md)。
验证命令：`./gradlew :app:testDebugUnitTest --tests '*Tieba*' :app:assembleDebug`。

---

_声明：本工具仅供个人学习及存档研究使用，请勿用于非法用途。请尊重版权与隐私。_

---

## 技术

1. 宿主端文件缓存 & 异步发送 (ZhihuHook.java)

- 引入了单线程池 (ExecutorService)，这样 Hook 层拦截到大数据后，不会阻塞知乎的 OkHttp
  网络请求，防止知乎界面卡顿。
- 本地缓存机制：拦截到数据后，不再直接抛给 ContentProvider，而是先用 JSONObject
  打包并写入知乎的沙盒缓存目录 mContext.getCacheDir() + "/index_record_cache"。
- 重试与删除机制
  (trySyncToApp)：写入缓存后立即尝试读取该目录下的所有文件并发送给“小茵记录”。如果发送成功（返回非
  null 的 Uri），则自动删除临时文件；如果你的 App
  被系统强杀导致发送失败，文件会继续留在知乎的目录中，直到下一次你打开知乎刷帖子时再次重试发送，彻底
  解决数据丢失问题！

2. 后台自动解析 (HookDataProvider.kt)

- 移除了以前那种“堆积在数据库，等用户打开 UI 才开始狂转圈解析”的逻辑。
- 采用 Kotlin Coroutines (providerScope.launch)。当“小茵记录”接收到知乎传来的数据时，哪怕 UI
  是关闭状态，ContentProvider 也会立刻在后台线程唤醒 DataProcessor 将原始数据转化为标准记录。

3. 高度泛用化数据库 (Entities.kt & DataProcessor.kt)

- 废弃了局限性大的 Article，启用了 UniversalRecord。
- 防止重复存储：在 DataProcessor.kt 的 processPendingData
  里面加上了核心的去重逻辑。现在针对每一条记录，会提取出一个全局唯一的 platformId（比如
  zhihu_answer_12345）。如果有重复数据进来，会执行更新覆盖而不是产生重复列表。
- 新增了 platform、recordType 以及非常灵活的 extraMetadata 字段，你以后加豆瓣、贴吧只需要往
  extraMetadata 里塞对应平台的 JSON，不需要改动数据库表结构。

4. UI 响应式适配 (HomeScreen.kt & HomeViewModel.kt)

- HomeViewModel 的轮询逻辑彻底删掉，改成了响应式的
  Flow。由于后台全自动解析，一旦有新数据存入数据库，界面的列表会自动向上冒出新数据（响应式编程）。
- 列表 UI 做了升级，支持从新表读取 platform 和 recordType 动态生成标签（例如显示：“知乎 answer” 或
  “知乎 article”）。

⚠️ 注意事项（测试前必看）：
因为我修改了数据库表结构（移除了 Article，新增了 UniversalRecord），ObjectBox在处理“丢弃旧表”时偶尔会留有死数据。你在编译运行并重新激活 Xposed 模块后，建议先在手机上“清除应用数据”一次（清空旧版 ObjectBox 残留），然后再打开使用。
