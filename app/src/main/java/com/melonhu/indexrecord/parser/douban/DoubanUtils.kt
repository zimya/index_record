package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.melonhu.indexrecord.markdown.toMarkdown
import com.melonhu.indexrecord.parser.zhihu.markdown.htmlToMdAst
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DoubanUtils {

    fun JsonObject.safeString(key: String): String {
        if (!has(key)) return ""
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return ""
        if (elem.isJsonPrimitive) {
            val prim = elem.asJsonPrimitive
            if (prim.isString) return prim.asString
            return prim.toString()
        }
        return elem.toString()
    }

    fun JsonObject.safeLong(key: String, default: Long = 0L): Long {
        if (!has(key)) return default
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asLong else prim.asString.toLongOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeDouble(key: String, default: Double = 0.0): Double {
        if (!has(key)) return default
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asDouble else prim.asString.toDoubleOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeStringList(key: String): List<String> {
        if (!has(key)) return emptyList()
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return emptyList()
        if (elem.isJsonArray) {
            val list = mutableListOf<String>()
            for (item in elem.asJsonArray) {
                if (item.isJsonPrimitive) {
                    val s = item.asString.trim()
                    if (s.isNotBlank()) list.add(s)
                } else if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    val name = obj.safeString("name")
                        .ifBlank { obj.safeString("title") }
                        .ifBlank { obj.safeString("cn_name") }
                    if (name.isNotBlank()) list.add(name)
                }
            }
            return list
        }
        if (elem.isJsonPrimitive) {
            val s = elem.asString.trim()
            if (s.isNotBlank()) return listOf(s)
        }
        return emptyList()
    }

    fun parseDoubanDate(dateStr: String?, fallback: Long = System.currentTimeMillis()): Long {
        if (dateStr.isNullOrBlank()) return fallback
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("GMT+8")
            }
            sdf.parse(dateStr)?.time ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun doubanHtmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""
        return try {
            htmlToMdAst(html).toMarkdown()
        } catch (_: Exception) {
            html
        }
    }

    data class UserInfo(val id: String, val name: String, val avatar: String)

    fun extractUser(obj: JsonObject): UserInfo {
        val userObj = when {
            obj.has("author") && obj.get("author").isJsonObject -> obj.getAsJsonObject("author")
            obj.has("user") && obj.get("user").isJsonObject -> obj.getAsJsonObject("user")
            else -> null
        }
        val id = userObj?.safeString("id")?.ifBlank { userObj.safeString("uid") } ?: ""
        val name = userObj?.safeString("name")?.ifBlank { "豆瓣用户" } ?: "豆瓣用户"
        val avatar = userObj?.safeString("avatar")?.ifBlank { userObj.safeString("avatar_url") } ?: ""
        return UserInfo(id = id, name = name, avatar = avatar)
    }
}
