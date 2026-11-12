package me.melon.record.parser

import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanParser
import me.melon.record.parser.x.XParser
import me.melon.record.parser.weibo.WeiboParser
import me.melon.record.parser.tieba.TiebaParser
import me.melon.record.parser.threads.ThreadsParser
import me.melon.record.parser.zhihu.ZhihuParser
import me.melon.record.parser.reddit.RedditParser

/**
 * 平台解析器注册中心。
 * 负责根据数据来源及 URL 特征分发给对应的应用解析器（如知乎、豆瓣、贴吧等）。
 */
object ParserRegistry {

    private val parsers: List<RawDataParser> = listOf(
        ZhihuParser,
        DoubanParser,
        XParser,
        TiebaParser,
        WeiboParser,
        ThreadsParser,
        RedditParser
    )

    fun findParser(cleanUrl: String, raw: RawData): RawDataParser? {
        return parsers.firstOrNull { it.canHandle(cleanUrl, raw) }
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord>? {
        val parser = findParser(cleanUrl, raw) ?: return null
        return parser.parse(raw, cleanUrl)
    }
}
