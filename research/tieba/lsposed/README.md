# 本次 LSPosed 实机抓取

2026-11-11，已连接 root 手机，百度贴吧 `22.9.1.0`。
本次仅使用项目 `TiebaHook.java`，未使用 Frida。此目录原始响应、截图和 UI dump
仅留在本机，避免将会话信息或其他个人数据提交到仓库。

1. 构建并 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
2. 确认 LSPosed 对 `com.baidu.tieba` 已启用模块，重启贴吧进程。
3. 点击推荐帖子“谁能告诉我外战看内啥来着？”，滑动至回复，点击“查看全部11条回复”。
4. 从 `/data/user/0/com.baidu.tieba/cache/index_record_queue` 复制 `.bin/.meta`。
   原始 protobuf 未转码；meta 第一行标注响应类别 URL（无法取到请求 URL 时使用类别地址）。
5. 启动记录 App，再启动贴吧并回记录 App 下拉刷新；确认全部队列已同步删除。
6. 在记录 App 中打开楼层、点击“回主帖”，核对 15 个本地楼层、5 楼以及 11 条楼中楼。

关键原始响应：

- `1794417060889_19771_127037029400026.bin`：7,486 bytes，PbPage，无帖子数据。
- `1794417075762_19771_127051900145646.bin`：149,242 bytes，PbList。
- `1794417075778_19771_127051915726844.bin`：156,592 bytes，PbList，同一帖子。
- `1794417546508_19771_127522652130362.bin`：41,963 bytes，PbFloor，1 个父楼和 11 条楼中楼。

真实字段白名单投影、SHA-256 和回归测试位于
`app/src/test/resources/tieba/`。投影去掉认证、广告、遥测和无关资料，不改保留字段值。
两个列表包含相同 34 个记录；加楼中楼响应后合并为 41 个唯一记录。
列表回帖楼号全为 0；独立楼中楼响应才提供该父楼为 5 楼。

## 主帖收藏数

两个 PbList 原始响应的 `data.thread`（外层 tag 2 → tag 1）都包含
`collect_num`（tag 190，uint32）= **19**，主帖 ID 为 `11180147101`。
字段编号已通过本机 `base.apk` 的 `tbclient.ThreadInfo` 注解核对。
`collect_status`（tag 50）= 0 是当前用户的收藏状态，不是收藏总数；
另一个字段 `storecount`（tag 68）未出现在这两个响应中。
PbFloor 的 `data.thread` 未发送 tag 190，三个 PbPage dump 未提供主帖收藏数。

解析后仅在主帖 `extraMetadata.favlists_count` 写入实际存在的收藏数，
由详情页现有星形统计展示。明确的 0 会保留，缺失字段不补 0。
投影脚本白名单保留 tag 190，真实二进制测试断言收藏数为 19。
已处理的历史 RawData 会删除，旧记录需重新采集或重放 PbList 才能补齐此字段。
