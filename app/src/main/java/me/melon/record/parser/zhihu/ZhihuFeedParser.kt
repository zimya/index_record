package me.melon.record.parser.zhihu

import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import java.util.regex.Pattern

/**
 * 知乎推荐信息流（卡片模式）解析器。
 * 对应接口：https://api.zhihu.com/next-render
 */
object ZhihuFeedParser {

    private val PATTERN_ZHIHU_NEXT_RENDER = Pattern.compile("https?://api\\.zhihu\\.com/next-render/?")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_NEXT_RENDER.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val records = mutableListOf<UniversalRecord>()
        val jsonParams = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val dataArray = jsonParams.getAsJsonArray("data") ?: return emptyList()
        for (elem in dataArray) {
            if (!elem.isJsonObject) continue
            val record = ZhihuItemV2Parser.parse(elem.asJsonObject, raw)
            if (record != null) {
                records.add(record)
            }
        }
        return records
    }
}
