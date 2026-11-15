# 项目详情

这是一个由jetpack compose编写的全自动记录社交媒体中浏览记录的软件。技术栈是Kotlin、Lsposed、ObjectBox、Jsoup、commonMark。原理是hook App中的okhttp响应，将数据缓存进cache私有目录，然后打开app时通过广播获取宿主App的私有目录数据，存进ObjectBox数据库中。启用这个App需要申请Lsposed作用域，并且大概率需要root权限。

# 目录结构

./research 下面是抓包得到的响应数据，用于分析
./app/src/test/java/com/melonhu/indexrecord 是App测试
./app/src/main/java/com/melonhu/indexrecord 是App源码

# 如何自动化验证

先`adb devices`查看是否链接手机。链接了手机的话，我的手机一定存在root权限。你可以先用`adb shell su -c 'ls -la /data/user/0/com.twitter.android'`查看私有目录数据，然后可以通过 `adb shell monkey` 来启动各种App，观察cache文件夹中保存的bin、meta文件。你可以使用 `adb shell input` `adb shell uiautomator` 等命令来操作手机，观察手机屏幕。

# 逆向环境

大部分情况下你不需要逆向，但如果有必要的话，macOS环境下配置了jadx、strings、ghidraRun等工具可以调用。Apk可以从手机中直接获取。

# 开发约定

## 核心代码原则

- **简洁直接，拒绝过度防御**：尽量少用防御性编程，能用一个判断解决的就不要用多个判断，代码要简洁。准确定位问题的根本原因，直击核心逻辑，不堆砌没有实际场景依据的猜测性容错或多层冗余兜底。

## Hook 开发约定

基于现代 LibXposed 标准（API 102，适配 LSPosed Next 及 JingMatrix Vector 等现代框架）。模块主入口为 `hook/IndexRecordModule.java`，配置位于 `META-INF/xposed/`（`java_init.list`、`scope.list`、`module.prop`）。

各平台 `hook/*Hook.java` 保留抓取点、URL 筛选和平台预处理，通过组合使用公共组件，不继承 `BaseHook`：

- `hook/HookContext.java`：封装进程生命周期、`Application.attach(Context)` 拦截、日志与异步单线程 Executor。
- `hook/OkHttpHookHelper.java`：模块化 OkHttp 拦截器注入、Proxy 构建、`IOException` 解包保护与响应体提取。
- `hook/ReflectionHelper.java`：现代反射与多重 hook 工具集，兼容 LibXposed API 102。
- `sync/HookCaptureClient.java`：同步广播、旧缓存迁移、Binder 文件直传与 ContentProvider 回退。使用宿主原有的单线程 executor，不依赖 ObjectBox 或 UI。
- `sync/CaptureQueue.java`：原始字节落盘、meta 兼容读取和队列消费。先关闭完整 payload，再将 `.meta.tmp` 重命名为 `.meta` 发布；meta 四行为 URL、宿主包名、内容类型、捕获时间，仍兼容旧两/三行格式。
- 队列仅在接收端确认成功后删除；传输失败保留并结束本轮。损坏 meta 保留并跳过，不阻塞后续有效项；未发布文件不参与同步。同步使用文件锁排除跨进程并发消费者，但确认丢失后的重试仍可能重复投递，不保证 exactly-once。
- Reddit 的整队列广告预扫描和逐条清洗分别通过 `beforeSync` / `payloadFilter` 接入；主动过滤与传输失败是不同结果。诊断 dump 使用独立目录，不参与正常同步。
- 旧 `index_record_cache` 迁移提交成功后才删除源文件；无法迁移的文件保留并记录日志。X 的队列软链接、微博实际宿主包名，以及 Threads/贴吧原始正文均保留。

公共队列与 Hook 规则的 JVM 回归测试：

```sh
./gradlew :app:testDebugUnitTest --tests 'com.melonhu.indexrecord.sync.CaptureQueueTest'
./gradlew :app:testDebugUnitTest --tests 'com.melonhu.indexrecord.hook.HookTargetUrlTest'
```

## 解析与持久化边界

- 生产处理使用 `RawDataParser.parseResult()` / `ParserRegistry.parseResult()`，不根据列表型 `parse()` 的空结果决定原始数据删除。`Success`（包括合法空响应）与带原因的 `Ignored` 可消费原文；`Failure` 保留原文；`Partial` 写入有效记录但保留完整原文及诊断。诊断不复制原始 payload。此契约不等于完整的字段级 schema 校验，既有安全读取和主动跳过规则仍由各平台定义。
- 保留的原文下次处理时可重试；一次处理按 ID 顺序扫描，跳过本轮已尝试的保留项，不因坏数据阻塞后续捕获。暂不提供持久化隔离、重试上限或诊断管理界面；部分成功的重试可能再次进入合并流程。
- 默认结果入口校验 JSON 对象；知乎 appview HTML、Threads 流式响应、Reddit 批响应和贴吧二进制信封由平台入口处理，不能一律要求 JSON 对象。
- `logic/TwitterPersistence.kt` 通过通用仓库能力补全 X 关系，查询和回写失败交由调用方事务回滚；`parser/x/XRelations.kt` 仅保留纯元数据合并。X 详情解析（`ConversationTimeline`）仅保留当前主推文及回帖线程，通过模块 `entry_id`/`entryId` 与 `client_event_info` 过滤 `related_tweet`/`RelatedTweet` 附加推荐，保持单点准确判断，不增加多余防御层。`logic/DoubanPersistence.kt` 封装标题补全证据和进程级状态，公共协调器只路由 `CompletionEvidence`。豆瓣既有缓存键、补全顺序及状态不随事务回滚的行为保持不变。

## 详情数据契约

详情页统一采用 `Repository → 完整快照 → ViewModel StateFlow → Compose`，普通记录、贴吧、豆瓣条目、评论和知乎问题保留各自的查询策略，不在 Composable 中查询或订阅 ObjectBox。

- **身份**：`data/model/RecordIdentity.kt` 集中解释存储 ID。Twitter 的 tweet/comment 捕获角色属于同一个内容身份；读取同身份的多个实体时，按捕获时间、发布时间、存储 ID 确定最新值，当前入口的导航角色保持不变。
- **关系**：`RecordRelations.kt` 是 `parentId` 的语义边界，区分回答所属问题、回复对象、内容所属条目、楼层所属主帖和内容引用。关联卡片接收显式关系及 `RECORDED / REFERENCE_ONLY / MISSING` 目标状态，不再用目标平台或类型推断关系。豆瓣未捕获条目可提供显式引用；所有平台缺失目标只显示提示，不构造捕获记录。
- **快照与刷新**：`data/repository/RecordObservation.kt` 统一订阅本地记录变更，合并突发通知，在 IO 线程的同一个读事务中重建完整快照。正文、统计、评论和关联内容不分别发布。单次读失败不终止变更监听，下一次变更可恢复。
- **不可变状态**：快照只暴露全 `val` 的 `DetailRecord` 和防御性复制的不可修改集合。`RecordContent` 为实体与快照提供共享的只读消费契约，它本身不承诺实现对象不可变。普通详情直接渲染快照，公共展示函数不依赖 ObjectBox 实体；导航需要实体时才通过 `toEntity()` 创建新值，不能修改快照。贴吧特殊页仍保留现有实体适配路径。
- **元数据与统计**：平台领域元数据使用 `TiebaMetadata`、`DoubanSubjectMetadata` 等命名字段，隐藏原始 JSON，不依赖 parser。展示统计使用 `RecordStatistic` / `CommentStatistics` 等契约，保留各平台未知值、零值、负分和排序语义，不把所有平台字段继续塞入通用元数据类。
- **加载与生命周期**：各页使用 `DetailLoadState.Loading / Ready / Failed`，通过 `DetailLoadContent` 统一显示加载、保留快照的错误反馈和重试入口。`DetailNavigationViewModel` 为每次导航创建独立条目；`DetailViewModelScope` 使用该条目的 ViewModelStore，被关联内容覆盖时保留，弹出且退出动画结束后清理。`SaveableStateProvider` 按条目保存滚动位置、排序、筛选及展开状态，不按裸平台 ID 共享位置。此机制支持栈内返回和配置变化，不承诺进程被系统终止后的导航栈恢复。

## 首页与统计策略

- `HomeViewModel` 仅协调首页搜索、筛选及加载状态；`HomeRecordRepository` 负责本地查询与订阅，`HomeQueryPolicy` 负责纯筛选，`HomeSyncCoordinator` 负责宿主唤醒和捕获同步，详情导航独立管理。
- 首页分组身份及标签由 `HomeRecordGroupPresentation` 复用关系与平台元数据契约解释，UI 仅聚合连续同组记录；缺少目标身份时不以标题充当身份。评论归属由 Repository 选择，UI 评论树只处理结构、去重与排序，不再重复执行 Reddit 归属筛选。
- 首页默认仅显示主内容；非空搜索或任一显式筛选（日期、应用、类型）会允许楼层、回复和评论参与匹配。类型筛选使用 `platform:recordType` 展示身份，例如知乎评论、贴吧楼层、X 评论，不修改数据库中的原始类型。
- `RecordContentPolicy.isPrimaryContent` 显式声明主内容资格：知乎问题/回答/文章/想法，贴吧主帖，Reddit 帖子，X 推文，微博博文，Threads 主帖，豆瓣小组话题/长评/短评/条目。未声明的平台或类型不自动获得默认资格，新增平台需同步补充策略与测试。
- 设置中的“统计回复和评论”默认关闭；开启后将本地回复和评论纳入快照数、字数、分布和连续天数。统计页读取不可变阅读历史，按各次阅读的时间及当时内容计算；同一内容跨日或同日重复阅读均分别计数，后续更新不挪动旧日期、不重算旧快照字数。不累加平台点赞、回复等互动数字。此开关不影响采集、存储、搜索或平台计数字段映射。
- **阅读定义**：默认将捕获记作阅读，捕获时间即阅读时间；历史列表、快照和统计统一使用此口径。Reddit READ 主帖是例外：一次详情访问只计一次阅读，评论及其他平台仍按捕获记录；各平台已有记录模式继续控制记录范围。
- **历史存储**：`UniversalRecord` 继续作为可合并的当前内容索引，以保留搜索、关联与评论查询策略；`RecordSnapshot` 独立追加捕获时的字段，不随内容补全或关系回写修改。默认快照是在当前索引合并之前冻结的本次解析结果，因此稀疏响应可能没有正文，不拿其他时刻的内容伪装成本次捕获。Reddit READ 主帖按下述访问与原始内容关联规则保存，不将辅助响应单独记作阅读。快照阅读不重新查询最新正文；diff 比较保存字段而非整页关联回复，长文本采用有界差异算法，必要时降级为变化区段替换。
- **Reddit READ 访问与暂存**：主帖内容更新与阅读事件分离。评论详情请求的 `pdpConsistencyToken` 标识一次访问，同 token 的截断评论、完整评论、重试等不重复计主帖阅读；缺少 token 的旧捕获按原始捕获时间的 30 秒窗口合并，这是网络侧启发式，不保证识别快速重开或预加载。首页预览、正文补全、页面配置和已读标记本身不新增主帖阅读。访问等待包含完整正文或媒体的原始响应（明确返回的空正文有效，未请求正文的配置/评论壳无效），从访问前 30 秒、后 120 秒的候选中按时间就近关联；每个来源最多用于一次访问，快照使用原始来源内容、首次访问时间，不拿当前索引的合成正文填充。不同 token 的重复阅读即使正文相同也分别保留。评论仍按原始捕获及 READ 准入保存历史，PUSH 仍逐捕获记录。
- **Reddit READ 保留边界**：正文候选、待完成访问及未确认评论共用 `PendingRecordSnapshot`，不参与历史或统计；共计最多保留 5000 条，在关联前及处理后清理。评论按首次暂存时间最多保留 7 天；主帖正文候选和访问另以原始捕获时间限制最长 7 天（延迟到达超过 7 天不新建主帖历史），防止淘汰后保留的 Partial 原文重试刷新保留期。访问键与已消费来源键随正式主帖快照持久化，跨处理批次、进程重启及 Partial 重试不重复计数。升级前仅剩合成主帖缓冲而没有访问及原始正文时，不将其伪造为新阅读；已有正式历史不自动清理，且不受暂存上限影响。
- **旧数据与重试**：无快照的旧索引作为已有阅读快照展示，首次覆盖或关联回写前按需冻结，不做启动迁移或改动已有实体字段。升级前已经被覆盖的版本无法恢复。快照与索引及原文删除处于同一写事务；默认同一原文的部分成功重试以原文 ID、内容身份及出现序号幂等追加，不按正文去重；Reddit READ 主帖则使用访问键和原始来源键。队列确认丢失后重新投递成新原文仍可能新增重复捕获，不承诺 exactly-once。
- 详情中的“本地已记录 N 条”仅表示本地记录数量，平台返回的回复总数独立保留在统计展示契约中。未知计数不伪造为零；有效零值保留。

### 内容、回复与展示语义

- **正文**：解析器必须设置 `UniversalRecord.contentFormat`，取值为 `plain_text / html / markdown / markdown_math`。`RecordBody.kt` 统一解释格式；详情渲染、评论、关联摘要及首页正文搜索共用同一 AST 语义，隐藏链接地址不属于正文搜索文本。保存/合并正文时必须同时保存所选正文的格式。格式必须是已声明的四种值；空值和未知格式视为无效数据，不按平台猜测，也不回退为纯文本。新增非空存储字段时，必须先备份并一次性修复现有数据库，再运行新模型；Kotlin 默认参数不能填补 ObjectBox 旧行中的缺失列。本项目不在 App 中保留旧格式兼容、启动迁移或废弃页面转发层。
- **结构和入口**：`ContentStructure.kt` 声明标题、关联内容和本地回复能力。普通评论和主内容统一使用 `RecordDetailScreen`，不再保留独立评论详情入口。贴吧保留楼层布局，复用正文展示、互动指标及作者/时间约定。问题和条目保留各自的集合布局。
- **回复状态**：`ReplySectionPresentation` 区分暂不支持、本地未采集、平台明确报告零条以及已有本地记录。平台计数、分页提示与本地数量独立展示；本地列表为空不表示平台没有评论，已捕获记录优先于零计数或未声明能力。通用树处理孤立节点、重复身份及循环父链，不伪造父节点，也不改写原始关系。Repository 必须提供有归属证据的候选集合，不能把无法确定所属内容的评论挂到任意帖子；Reddit 的 `post_id` 是其断链归属证据，而不是其他平台可以套用的假设。
- **元数据**：`PlatformMetadataAdapters.kt`、`EngagementMetadata.kt` 负责平台字段语义；调用方直接使用平台领域适配器，不保留元数据转发层。列表、详情和评论树使用相同统计契约，未知为 `null`，有效零值与 Reddit 负分保留。评论点赞不再保留无依据的历史特殊回退。新增字段应进入对应平台适配器及小展示模型，不在 UI 或 Repository 中重新解析 JSON。
- **公共词汇**：`PlatformCatalog` 提供平台名、类型短名及筛选名称；Threads 统一为帖子/回复，Twitter 统一显示 X。空作者显示“作者未知”，无效发布时间显示“发布时间未知”，不格式化为纪元日期。互动数字提供“点赞数”“平台回复数”等读屏语义，读出完整计数而非视觉缩写。

新增平台时，先补身份、关系、正文格式、结构能力及领域元数据测试，再实现所需的 Repository 查询策略，复用观察与加载契约，最后接入只收集状态的页面；不要新增按平台分流的评论详情。回归验证：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
