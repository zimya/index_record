<div align="center">
<picture>
<img alt="Icon" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="150">
</picture>
<h1>小茵记录 (Index Record)</h1>

<p align="center">
<a href="https://modules.lsposed.org/module/com.melonhu.indexrecord/">
<img src="https://img.shields.io/badge/LSPosed-Module-green.svg">
</a>
<a href="https://github.com/zimya/index_record/releases">
<img src="https://img.shields.io/github/v/release/zimya/index_record?&sort=semver">
</a>
<a href="https://github.com/zimya/index_record/releases">
<img src="https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.melonhu.indexrecord/total">
</a>
<a href="https://github.com/zimya/index_record/blob/main/LICENSE">
<img src="https://img.shields.io/github/license/zimya/index_record">
</a>
<a href="https://t.me/index_record_group">
<img src="https://img.shields.io/badge/telegram-blue?logo=telegram&logoColor=white">
</a>
</p>

</div>

> 上条的“知识”在诉说着。她拥有完全记忆能力的体质，对于一旦记住的东西绝对不会遗忘。所以她靠着这种体质，将十万三千本魔道书都记忆在脑海中。—— 《某魔法的禁书目录 · 第二卷》

**小茵记录** 是一个基于 Xposed 框架的 Android 应用，旨在为你自动存档在社交网络上浏览过的内容。

项目名称灵感来源于《魔法禁书目录》中的女主角 **茵蒂克丝 (Index)**，她拥有完全记忆能力。本应用的目标是成为你移动设备上的茵蒂克丝，帮你记住每一个刷过的瞬间，防止内容被作者删除或由于平台审查而消失，当然与茵蒂克丝不同的是，本应用不会每隔一年就删数据，而是将数据永久保存在你的设备中。_声明：本工具仅供个人学习及存档研究使用，请勿用于非法用途。请尊重版权与隐私。_

## 功能特性

- **无感记录**：基于 LSPosed，在你刷帖时自动捕获原始 JSON/HTML，或将部分客户端已解码的响应转换为可存档的数据。
- **本地存储**：所有数据均保存在本地 ObjectBox 数据库中，不上传至任何服务器，代码开源可供审查。
- **自动解析**：针对不同平台进行数据提取，还原标题、作者、正文等核心信息。
- **离线阅读**：即使原帖被删，你依然可以在应用内查看已存下的快照。
- **阅读历史与差异**：每次阅读都会保留当前时间戳的存档，还可以任意选择快照比较。

## 安装与使用

1. 确保你的设备已安装 Xposed 环境（如 LSPosed）。
2. 安装并激活 **小茵记录** 模块。
3. 在 LSPosed 中勾选目标应用（如知乎）。
4. 打开目标应用浏览内容，回到 **小茵记录** 下拉刷新即可看到存档。

### 平台支持

- [x] **知乎 / 知乎++**
    - [x] 知乎回答
    - [x] 知乎文章
    - [x] 知乎问题
    - [x] 知乎想法
- [x] **豆瓣**
    - [x] 豆瓣书评
    - [x] 豆瓣短评
    - [x] 豆瓣长评（影评/书评/乐评）
    - [x] 豆瓣广播
    - [x] 豆瓣动态
    - [x] 豆瓣日记
- [x] **百度贴吧 / Tieba Lite**
    - [x] 贴吧主帖
    - [x] 贴吧楼层
    - [x] 贴吧楼中楼
- [x] **Twitter (X)**
    - [x] 推文
    - [x] 推文回复
- [x] **微博**
    - [x] 博文
    - [x] 博文回复
    - [x] 微博超话
- [x] **Threads**
    - [x] thread
    - [x] 回复
- [x] **Reddit**
    - [x] 帖子
    - [x] 回复
- [x] **酷安**
    - [x] 酷安动态
    - [x] 酷安点评
    - [x] 酷安图文
    - [x] 酷安问答
- [x] **虎扑**
    - [x] 帖子
    - [x] 回复
    - [ ] 虎扑评分
- [x] **小黑盒**
    - [x] 帖子
    - [x] 回复
- [x] **Bluesky**
    - [x] 帖子
    - [x] 回复
- [x] **NGA玩家社区**
    - [x] 帖子
    - [x] 回复
- [ ] 微信读书
- [ ] Lofter
- [ ] Mastodon(长毛象)
- [ ] Medium
- [ ] Goodreads
- [x] **哔哩哔哩（国内版 / 国际版）**
    - [x] 图文
    - [x] 评论
    - [x] 视频详情
- [ ] 网易云音乐评论区
- [ ] DCard

**注意：本应用不支持且永远也不会支持小红书，如有需要该功能请自行fork。**

## 技术栈

- **Android**: Kotlin, Jetpack Compose, Material 3
- **Hooking**: Xposed Framework
- **Database**: ObjectBox
- **Networking**: OkHttp
- **Parsing**: Gson, Jsoup, CommonMark
