package app.ottermsg.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.MessageRecipientEntity
import kotlinx.coroutines.flow.Flow

/** Aggregate receipt counts of one outgoing group message (D23). */
data class ReceiptCounts(val total: Int, val delivered: Int, val read: Int)

/** One frozen recipient with its receipt times and display name, for message info. */
data class RecipientStatus(
    @ColumnInfo(name = "user_id") val userId: ByteArray,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "delivered_ms") val deliveredMs: Long?,
    @ColumnInfo(name = "read_ms") val readMs: Long?,
)

@Dao interface MessageRecipientDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(r: List<MessageRecipientEntity>)
    @Query("SELECT * FROM message_recipients WHERE message_local_id = :localId") suspend fun forMessage(localId: Long): List<MessageRecipientEntity>
    /** Returns 1 if [userId] is in the frozen set and had no delivery time yet. */
    @Query("""UPDATE message_recipients SET delivered_ms = :ms
              WHERE message_local_id = :localId AND user_id = :userId AND delivered_ms IS NULL""")
    suspend fun setDelivered(localId: Long, userId: ByteArray, ms: Long): Int
    /** READ implies delivered. */
    @Query("""UPDATE message_recipients SET read_ms = :ms, delivered_ms = COALESCE(delivered_ms, :ms)
              WHERE message_local_id = :localId AND user_id = :userId AND read_ms IS NULL""")
    suspend fun setRead(localId: Long, userId: ByteArray, ms: Long): Int
    @Query("""SELECT COUNT(*) AS total, COUNT(delivered_ms) AS delivered, COUNT(read_ms) AS read
              FROM message_recipients WHERE message_local_id = :localId""")
    suspend fun counts(localId: Long): ReceiptCounts
    @Query("""SELECT COUNT(*) AS total, COUNT(delivered_ms) AS delivered, COUNT(read_ms) AS read
              FROM message_recipients WHERE message_local_id = :localId""")
    fun observeCounts(localId: Long): Flow<ReceiptCounts>
    @Query("""SELECT r.user_id, c.display_name, r.delivered_ms, r.read_ms FROM message_recipients r
              LEFT JOIN contacts c ON c.user_id = r.user_id WHERE r.message_local_id = :localId""")
    fun observeRecipients(localId: Long): Flow<List<RecipientStatus>>
}
