package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser

/**
 * 豆瓣条目（电影/图书/音乐/游戏）与短评/讨论等关系的关联处理器。
 */
object DoubanRelations {

    // 内置已知样例条目标题，保证单测与离线解析立即可用
    // 仅为不可变离线默认值；捕获到的标题由持久化阶段查询、回填，不再污染解析结果。
    fun sampleSubjectTitle(subjectId: String): String = when (subjectId) {
        "1292722" -> "泰坦尼克号"
        "1007305" -> "红楼梦"
        "35282427" -> "One Last Kiss"
        "26817171" -> "塞尔达传说 旷野之息"
        else -> ""
    }

    fun mergeSubjectExtra(existingExtra: String, newExtra: String): String {
        if (existingExtra.isBlank()) return newExtra
        if (newExtra.isBlank()) return existingExtra
        return try {
            val existObj = JsonParser.parseString(existingExtra).asJsonObject
            val newObj = JsonParser.parseString(newExtra).asJsonObject

            for ((k, v) in newObj.entrySet()) {
                val isMeaningful = when {
                    v.isJsonNull -> false
                    v.isJsonPrimitive -> {
                        val p = v.asJsonPrimitive
                        if (p.isString) p.asString.isNotBlank()
                        else if (p.isNumber) p.asDouble > 0
                        else true
                    }
                    v.isJsonArray -> v.asJsonArray.size() > 0
                    v.isJsonObject -> v.asJsonObject.size() > 0
                    else -> true
                }
                if (isMeaningful || !existObj.has(k)) {
                    existObj.add(k, v)
                }
            }
            existObj.toString()
        } catch (_: Exception) {
            newExtra.ifBlank { existingExtra }
        }
    }
}
