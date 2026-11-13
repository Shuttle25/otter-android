package app.ottermsg.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.database.dao.AttachmentDao
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.dao.EndpointStatDao
import app.ottermsg.core.database.dao.GroupDao
import app.ottermsg.core.database.dao.MessageRecipientDao
import app.ottermsg.core.database.dao.ConversationDao
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.database.dao.NetLogDao
import app.ottermsg.core.database.dao.MessageDao
import app.ottermsg.core.database.dao.OutboxDao
import app.ottermsg.core.database.dao.SeenEnvelopeDao
import app.ottermsg.core.database.dao.SettingsDao
import app.ottermsg.core.database.dao.SignalKvDao
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.EndpointStatEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageRecipientEntity
import app.ottermsg.core.database.entity.MyDistributionEntity
import app.ottermsg.core.database.entity.PendingGroupEnvelopeEntity
import app.ottermsg.core.database.entity.PendingGroupUpdateEntity
import app.ottermsg.core.database.entity.SenderKeySharedEntity
import app.ottermsg.core.database.entity.ManifestEntity
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.NetLogEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.entity.SeenEnvelopeEntity
import app.ottermsg.core.database.entity.SettingEntity
import app.ottermsg.core.database.entity.SignalKvEntity

@Database(
    entities = [AccountEntity::class, ManifestEntity::class, ContactEntity::class, ConversationEntity::class,
        MessageEntity::class, OutboxEntity::class, SeenEnvelopeEntity::class, SignalKvEntity::class, SettingEntity::class, EndpointStatEntity::class,
        GroupEntity::class, GroupMemberEntity::class, PendingGroupUpdateEntity::class, PendingGroupEnvelopeEntity::class,
        SenderKeySharedEntity::class, MyDistributionEntity::class, MessageRecipientEntity::class, AttachmentEntity::class,
        NetLogEntity::class],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)],
)
abstract class OtterDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun manifestDao(): ManifestDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenEnvelopeDao(): SeenEnvelopeDao
    abstract fun signalKvDao(): SignalKvDao
    abstract fun settingsDao(): SettingsDao
    abstract fun endpointStatDao(): EndpointStatDao
    abstract fun groupDao(): GroupDao
    abstract fun messageRecipientDao(): MessageRecipientDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun netLogDao(): NetLogDao
}
