package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The winning verified REKEY of [oldUserId] (D111): the later [createdMs] wins, then the lower [recordId] (the record
 * key is network id ‖ id, as on the nodes). [revokedMs] = the earliest REKEY of [oldUserId] seen, which revokes it like
 * a REVOCATION created then. [appliedMs] = 0 until the chat moved to [newUserId] (its entry was known).
 */
@Entity(tableName = "rekeys")
data class RekeyEntity(
    @PrimaryKey @ColumnInfo(name = "old_user_id") val oldUserId: ByteArray,
    @ColumnInfo(name = "new_user_id") val newUserId: ByteArray,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "record_id") val recordId: ByteArray,
    @ColumnInfo(name = "revoked_ms") val revokedMs: Long,
    @ColumnInfo(name = "applied_ms", defaultValue = "0") val appliedMs: Long = 0,
)
