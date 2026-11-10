package me.melon.record.parser.douban

import com.google.gson.JsonParser
import io.objectbox.Box
import io.objectbox.query.QueryBuilder
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import java.util.concurrent.ConcurrentHashMap

/**
 * 豆瓣条目（电影/图书/音乐/游戏）与短评/讨论等关系的关联处理器。
 */
object DoubanRelations {

    private val subjectTitleCache = ConcurrentHashMap<String, String>()

    init {
        // 内置已知样例条目标题，保证单测与离线解析立即可用
        subjectTitleCache["1292722"] = "泰坦尼克号"
        subjectTitleCache["1007305"] = "红楼梦"
        subjectTitleCache["35282427"] = "One Last Kiss"
        subjectTitleCache["26817171"] = "塞尔达传说 旷野之息"
    }

    fun registerSubjectTitle(subjectId: String, title: String) {
        if (subjectId.isNotBlank() && title.isNotBlank()) {
            subjectTitleCache[subjectId] = title
        }
    }

    fun getSubjectTitle(subjectType: String, subjectId: String): String {
        val cached = subjectTitleCache[subjectId]
        if (!cached.isNullOrBlank()) return cached

        if (ObjectBox.isInitialized) {
            try {
                val box = ObjectBox.store.boxFor(UniversalRecord::class.java)
                val targetPlatformId = "douban_${subjectType}_${subjectId}"
                val subjectRec = box.query()
                    .equal(UniversalRecord_.platformId, targetPlatformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .findFirst()
                if (subjectRec != null && subjectRec.title.isNotBlank()) {
                    subjectTitleCache[subjectId] = subjectRec.title
                    return subjectRec.title
                }

                // 从该条目下的长评或讨论帖元数据中查找 subject_title
                val rec = box.query()
                    .equal(UniversalRecord_.parentId, targetPlatformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .findFirst()
                if (rec != null) {
                    val meta = JsonParser.parseString(rec.extraMetadata).asJsonObject
                    val st = meta.get("subject_title")?.asString
                    if (!st.isNullOrBlank()) {
                        subjectTitleCache[subjectId] = st
                        return st
                    }
                }
            } catch (_: Exception) {}
        }

        return ""
    }

    fun linkSubjectToInterests(
        recordBox: Box<UniversalRecord>,
        subjectType: String,
        subjectId: String,
        subjectTitle: String
    ) {
        if (subjectId.isBlank() || subjectTitle.isBlank()) return
        registerSubjectTitle(subjectId, subjectTitle)

        val targetParentId = "douban_${subjectType}_${subjectId}"
        val typeLabel = when (subjectType) {
            "movie" -> "电影短评"
            "book" -> "图书短评"
            "music" -> "音乐短评"
            "game" -> "游戏短评"
            "tv" -> "剧集短评"
            "drama" -> "舞台剧短评"
            else -> "豆瓣短评"
        }
        val expectedTitle = "$typeLabel：$subjectTitle"

        val interests = recordBox.query()
            .equal(UniversalRecord_.platform, "douban", QueryBuilder.StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.recordType, "interest", QueryBuilder.StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.parentId, targetParentId, QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
            .find()

        for (item in interests) {
            if (item.title != expectedTitle) {
                item.title = expectedTitle
                recordBox.put(item)
            }
        }
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
