package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanParser
import com.melonhu.indexrecord.parser.x.XParser
import com.melonhu.indexrecord.parser.weibo.WeiboParser
import com.melonhu.indexrecord.parser.tieba.TiebaParser
import com.melonhu.indexrecord.parser.threads.ThreadsParser
import com.melonhu.indexrecord.parser.zhihu.ZhihuParser
import com.melonhu.indexrecord.parser.reddit.RedditParser
import com.melonhu.indexrecord.parser.coolapk.CoolapkParser
import com.melonhu.indexrecord.parser.hupu.HupuParser
import com.melonhu.indexrecord.parser.xiaoheihe.XiaoheiheParser

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
        RedditParser,
        CoolapkParser,
        HupuParser,
        XiaoheiheParser
    )

    fun findParser(cleanUrl: String, raw: RawData): RawDataParser? {
        return parsers.firstOrNull { it.canHandle(cleanUrl, raw) }
    }

    /** List-only facade for existing consumers; raw consumption must use parseResult. */
    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt("registry") {
        val parser = findParser(cleanUrl, raw)
            ?: return@attempt ParseResult.Ignored("No parser supports this capture")
        parser.parseResult(raw, cleanUrl)
    }
}
