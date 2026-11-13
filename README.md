<div align="center">
<picture>
<img alt="Icon" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="150">
</picture>
<h1>小茵记录 (Index Record)</h1>

</div>

> 上条的“知识”在诉说着。她拥有完全记忆能力的体质，对于一旦记住的东西绝对不会遗忘。所以她靠着这种体质，将十万三千本魔道书都记忆在脑海中。—— 《某魔法的禁书目录 · 第二卷》

**小茵记录** 是一个基于 Xposed 框架的 Android 应用，旨在为你自动存档在社交网络上浏览过的内容。

项目名称灵感来源于《魔法禁书目录》中的女主角 **茵蒂克丝 (Index)**，她拥有完全记忆能力。本应用的目标是成为你移动设备上的茵蒂克丝，帮你记住每一个刷过的瞬间，防止内容被作者删除或由于平台审查而消失，当然与茵蒂克丝不同的是，本应用不会每隔一年就删数据，而是将数据永久保存在你的设备中。_声明：本工具仅供个人学习及存档研究使用，请勿用于非法用途。请尊重版权与隐私。_

## 功能特性

- **无感记录**：基于 OkHttp Hook 技术，在你刷帖时自动在后台捕获原始 JSON/HTML 数据。
- **本地存储**：所有数据均保存在本地 ObjectBox 数据库中，不上传至任何服务器，代码开源可供审查。
- **自动解析**：针对不同平台进行数据提取，还原标题、作者、正文等核心信息。
- **离线阅读**：即使原帖被删，你依然可以在应用内查看已存下的快照。

## 安装与使用

1. 确保你的设备已安装 Xposed 环境（如 LSPosed）。
2. 安装并激活 **小茵记录** 模块。
3. 在 LSPosed 中勾选目标应用（如知乎）。
4. 打开目标应用浏览内容，回到 **小茵记录** 下拉刷新即可看到存档。

## 技术栈

- **Android**: Kotlin, Jetpack Compose, Material 3
- **Hooking**: Xposed Framework
- **Database**: ObjectBox
- **Networking**: OkHttp
- **Parsing**: Gson, Jsoup, CommonMark

## Hook 开发约定

各平台 `*Hook.java` 保留抓取点、URL 筛选和平台预处理，通过组合使用公共组件，不继承 `BaseHook`：

- `sync/HookCaptureClient.java`：同步广播、旧缓存迁移、Binder 文件直传与 ContentProvider 回退。使用宿主原有的单线程 executor，不依赖 ObjectBox 或 UI。
- `sync/CaptureQueue.java`：原始字节落盘、meta 兼容读取和队列消费。先关闭完整 payload，再将 `.meta.tmp` 重命名为 `.meta` 发布；meta 四行为 URL、宿主包名、内容类型、捕获时间，仍兼容旧两/三行格式。
- 队列仅在接收端确认成功后删除；传输失败保留并结束本轮。损坏 meta 保留并跳过，不阻塞后续有效项；未发布文件不参与同步。同步使用文件锁排除跨进程并发消费者，但确认丢失后的重试仍可能重复投递，不保证 exactly-once。
- Reddit 的整队列广告预扫描和逐条清洗分别通过 `beforeSync` / `payloadFilter` 接入；主动过滤与传输失败是不同结果。诊断 dump 使用独立目录，不参与正常同步。
- 旧 `index_record_cache` 迁移提交成功后才删除源文件；无法迁移的文件保留并记录日志。X 的队列软链接、微博实际宿主包名，以及 Threads/贴吧原始正文均保留。

公共队列的 JVM 回归测试：

```sh
./gradlew :app:testDebugUnitTest --tests 'me.melon.record.sync.CaptureQueueTest'
```

## 详情数据契约

详情页统一采用 `Repository → 完整快照 → ViewModel StateFlow → Compose`，普通记录、贴吧、豆瓣条目、评论和知乎问题保留各自的查询策略，不在 Composable 中查询或订阅 ObjectBox。

- **身份**：`data/model/RecordIdentity.kt` 集中解释存储 ID。Twitter 的 tweet/comment 捕获角色属于同一个内容身份；读取同身份的多个实体时，按捕获时间、发布时间、存储 ID 确定最新值，当前入口的导航角色保持不变。
- **关系**：`RecordRelations.kt` 是 `parentId` 的语义边界，区分回答所属问题、回复对象、内容所属条目、楼层所属主帖和内容引用。新增平台先明确关系类型与缺失策略，不在 UI 中解释 ID 前缀。豆瓣未捕获条目可提供显式引用；缺失的 Reddit 父节点只显示提示，不构造捕获记录。
- **快照与刷新**：`data/repository/RecordObservation.kt` 统一订阅本地记录变更，合并突发通知，在 IO 线程的同一个读事务中重建完整快照。正文、统计、评论和关联内容不分别发布。单次读失败不终止变更监听，下一次变更可恢复。
- **不可变状态**：快照只暴露全 `val` 的 `DetailRecord` 和防御性复制的不可修改集合。ObjectBox 实体只用于存储；旧渲染组件或导航需要实体时通过 `toEntity()` 创建新值，不能修改快照。
- **元数据与统计**：平台领域元数据使用 `TiebaMetadata`、`DoubanSubjectMetadata` 等命名字段，隐藏原始 JSON，不依赖 parser。展示统计使用 `RecordStatistic` / `CommentStatistics` 等契约，保留各平台未知值、零值、负分和排序语义，不把所有平台字段继续塞入通用元数据类。
- **加载与生命周期**：各页使用 `DetailLoadState.Loading / Ready / Failed`，通过 `DetailLoadContent` 统一显示加载、保留快照的错误反馈和重试入口。`DetailNavigationViewModel` 为每次导航创建独立条目；`DetailViewModelScope` 使用该条目的 ViewModelStore，被关联内容覆盖时保留，弹出且退出动画结束后清理。`SaveableStateProvider` 按条目保存滚动位置、排序、筛选及展开状态，不按裸平台 ID 共享位置。此机制支持栈内返回和配置变化，不承诺进程被系统终止后的导航栈恢复。

## 首页与统计策略

- `HomeViewModel` 仅协调首页搜索、筛选及加载状态；`HomeRecordRepository` 负责本地查询与订阅，`HomeQueryPolicy` 负责纯筛选，`HomeSyncCoordinator` 负责宿主唤醒和捕获同步，详情导航独立管理。
- 首页默认仅显示主内容；非空搜索或任一显式筛选（日期、应用、类型）会允许楼层、回复和评论参与匹配。类型筛选使用 `platform:recordType` 展示身份，例如知乎评论、贴吧楼层、X 评论，不修改数据库中的原始类型。
- `RecordContentPolicy.isPrimaryContent` 显式声明主内容资格：知乎问题/回答/文章/想法，贴吧主帖，Reddit 帖子，X 推文，微博博文，Threads 主帖，豆瓣小组话题/长评/短评/条目。未声明的平台或类型不自动获得默认资格，新增平台需同步补充策略与测试。
- 设置中的“统计回复和评论”默认关闭；开启后将本地回复和评论纳入条目数、字数、分布和连续天数。统计页按捕获时间计算，不累加平台点赞、回复等互动数字。此开关不影响采集、存储、搜索或平台计数字段映射。
- 详情中的“本地已记录 N 条”仅表示本地记录数量，平台返回的回复总数独立保留在统计展示契约中。未知计数不伪造为零；有效零值保留。

新增平台时，先补身份、关系及领域元数据测试，再实现所需的 Repository 查询策略，复用观察与加载契约，最后接入只收集状态的页面。回归验证：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 路线图 (TODO)

### 平台支持

- [x] **知乎** (回答、文章、想法)
- [x] **豆瓣** (书评、影评、广播)
- [x] **百度贴吧** (主帖、楼层、楼中楼)
- [x] **Twitter (X)** (推文)
- [x] **微博** (博文)
- [x] **Threads**（thread）
- [x] **Reddit**
- [ ] 酷安
- [ ] 虎扑
- [ ] 小黑盒
- [ ] 微信读书
- [ ] Lofter
- [ ] Mastodon(长毛象)
- [ ] NGA玩家社区
- [ ] Bluesky
- [ ] Medium
- [ ] Goodreads
- [ ] 哔哩哔哩评论区
- [ ] 网易云音乐评论区
- [ ] DCard

**注意：本应用不支持且永远也不会支持小红书，如有需要该功能请自行fork。**

### 功能增强

- [x] **全文搜索**：支持对已记录的内容进行关键词搜索。
- [ ] **图片/视频缓存**：目前仅支持文本，未来考虑增加多媒体资源的本地化存档。
- [ ] **数据导出**：支持导出为 Markdown、PDF 或 JSON 格式。
- [ ] **多端同步**：考虑支持 WebDAV 或其他私有云同步方式。

### UI/UX 优化

- [x] **内容排版优化**：更好地渲染抓取到的 HTML/Markdown 内容。
- [ ] **分类与标签**：支持按平台、作者或自定义标签对记录进行分类。
- [x] **暗黑模式**：完美适配系统的深色主题。
