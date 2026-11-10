package me.melon.record.parser

import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord

/**
 * 平台原始数据解析器接口。
 * 每种支持的平台（如知乎、豆瓣、贴吧等）或特定的抓取类型应实现此接口。
 */
interface RawDataParser {
    val platform: String

    /**
     * 判断当前解析器是否能够处理该 URL 或数据
     */
    fun canHandle(cleanUrl: String, raw: RawData): Boolean

    /**
     * 将原始数据解析为一个或多个 UniversalRecord
     */
    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord>
}
