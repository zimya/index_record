package me.melon.record.ui.components

import com.google.gson.JsonObject
import me.melon.record.presentation.signedScore as presentationSignedScore
import me.melon.record.presentation.optionalLong as presentationOptionalLong
import me.melon.record.presentation.optionalEngagementCount as presentationOptionalEngagementCount
import me.melon.record.presentation.engagementCount as presentationEngagementCount
import me.melon.record.presentation.viewEngagementCount as presentationViewEngagementCount

// Compatibility facade; the parsing rules and their original comments live in presentation.
internal fun JsonObject.signedScore(): Long? = presentationSignedScore()
internal fun JsonObject.optionalLong(key: String): Long? = presentationOptionalLong(key)
internal fun JsonObject.optionalEngagementCount(key: String): Long? = presentationOptionalEngagementCount(key)
internal fun JsonObject.engagementCount(vararg keys: String): Long = presentationEngagementCount(*keys)
internal fun JsonObject.viewEngagementCount(platform: String): Long = presentationViewEngagementCount(platform)
