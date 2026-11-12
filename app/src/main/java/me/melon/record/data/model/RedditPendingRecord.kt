package me.melon.record.data.model

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

@Entity
data class RedditPendingRecord(
    @Id var id: Long = 0,
    @Unique var platformId: String = "",
    @Index var postId: String = "",
    var confirmed: Boolean = false,
    var recordJson: String = "",
    var updatedAt: Long = 0
)
