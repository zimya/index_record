## 目录结构

Kotlin package 与目录对应，页面入口及其专属实现放在同一功能目录：

```text
ui/
├── home/              # Home*：首页、列表、分组、同步进度
├── stats/             # Stats*：统计页面、图表、数据与应用展示映射
├── detail/
│   ├── record/        # RecordDetail*：记录详情、评论树、元数据
│   ├── subject/       # Subject*：条目详情及关联记录
│   ├── question/      # Question*：问题详情与回答
│   └── tieba/         # Tieba*：贴吧详情、楼层与回复
├── settings/          # SettingsScreen：设置页面
├── main/              # MainScreen：主界面导航
├── components/        # 跨页面共享组件
├── theme/             # 主题
└── VisibilityHolderActivity.kt
```

`VisibilityHolderActivity` 保留在 `com.melonhu.indexrecord.ui`，与 Manifest 的 `.ui.VisibilityHolderActivity`（全限定名 `com.melonhu.indexrecord.ui.VisibilityHolderActivity`）一致。应用入口 Activity 仍为 `com.melonhu.indexrecord.MainActivity`。

跨功能目录使用显式 import：例如贴吧详情引用 `detail.record.MetadataBadge`；`appLabel` / `colorFromPackage` 随 `StatsAppPresentation` 位于 `stats`。测试中的首页分组类型与函数引用 `ui.home`。包迁移不改变实现、可见性或页面行为。

## 分层约定

- `*Screen.kt`：页面入口、状态订阅、Scaffold、列表编排、导航与弹层。不要在入口里继续堆积数据库查询、JSON 解析或复杂绘制。
- 页面前缀文件（例如 `HomeTopBar.kt`、`StatsBarChart.kt`）：只属于该页面的展示区块或交互。优先使用普通参数和回调，不让展示组件直接依赖 ViewModel。
- `*Data.kt` / `*State.kt`：页面数据查询、订阅和生命周期适配。`*Metadata.kt`、`*Grouping.kt`、`*CommentTree.kt` 等存放相应解析或转换逻辑。
- `components/`：跨页面共享的 UI；`theme/`：主题定义。仅在确实复用且语义一致时提升为公共组件，不因为外观相近强行合并不同业务。
