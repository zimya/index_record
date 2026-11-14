package com.melonhu.indexrecord.data.model

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/** Original Reddit READ captures awaiting admission, never queried as visible history. */
@Entity
data class PendingRecordSnapshot(
    @Id var id: Long = 0,
    @Unique var key: String = "",
    @Index var platformId: String = "",
    var captureTime: Long = 0,
    var recordJson: String = "",
    @Index var createdAt: Long = 0
)

internal fun PendingRecordSnapshot.toSnapshot(): RecordSnapshot = RecordSnapshot(
    key = key, platform = "reddit", identityId = RecordIdentity.of("reddit", platformId).contentId,
    captureTime = captureTime, recordJson = recordJson
)
