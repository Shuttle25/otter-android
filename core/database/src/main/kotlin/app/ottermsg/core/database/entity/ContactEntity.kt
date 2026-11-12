package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: ByteArray,
    val username: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "account_pub") val accountPub: ByteArray,
    @ColumnInfo(name = "identity_pub") val identityPub: ByteArray,
    val verified: Boolean,
    @ColumnInfo(name = "identity_changed") val identityChanged: Boolean,
    val revoked: Boolean,
    @ColumnInfo(name = "entry_version") val entryVersion: Long,
    @ColumnInfo(name = "reset_ms", defaultValue = "0") val resetMs: Long = 0, // last SessionReset we sent (D53)
)
