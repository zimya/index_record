package com.melonhu.indexrecord.parser.x

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.parser.x.XUtils.safeLong

object XRelations {

    private val gson = Gson()

    /**
     * 合并推文的额外元数据（保留更高的互动量、补充字段等）
     */
    fun mergeTweetExtra(existing: String, incoming: String): String {
        val oldObj = try { JsonParser.parseString(existing).asJsonObject } catch (_: Exception) { JsonObject() }
        val newObj = try { JsonParser.parseString(incoming).asJsonObject } catch (_: Exception) { JsonObject() }

        for ((key, value) in oldObj.entrySet()) {
            if (!newObj.has(key)) {
                newObj.add(key, value)
            } else {
                // 对于计数类字段，取最大值
                if (key.endsWith("_count")) {
                    val oldVal = oldObj.safeLong(key)
                    val newVal = newObj.safeLong(key)
                    if (oldVal > newVal) {
                        newObj.addProperty(key, oldVal)
                    }
                }
            }
        }

        return gson.toJson(newObj)
    }

}
