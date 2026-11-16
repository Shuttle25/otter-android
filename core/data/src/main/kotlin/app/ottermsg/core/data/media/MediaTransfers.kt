package app.ottermsg.core.data.media

import androidx.room.withTransaction
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.BlobFetched
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Moves photo blobs while a session is up (like [Outbox]): uploads before the envelope is released (D48), downloads
 * by policy (D47) with digest check, and acknowledges verified downloads with BLOB_FETCHED so nodes can delete (D45).
 */
@Singleton
class MediaTransfers @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val files: MediaFiles,
    private val settings: Settings, private val network: NetworkInfo, private val gallery: GallerySaver,
    private val outbox: Outbox, private val clock: Clock,
) {
    private val dao = db.attachmentDao()
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()

    fun kick() { kicks.trySend(Unit) }

    /** Worker for one online session; returns only by cancellation. Re-checks auto-download at least once a minute. */
    suspend fun run(api: NodeApi): Nothing {
        while (true) {
            drainOnce(api)
            val next = dao.nextAttemptAt()
            val wait = if (next == null) Long.MAX_VALUE else (next - clock.now()).coerceAtLeast(1)
            withTimeoutOrNull(wait.coerceAtMost(60_000)) { kicks.receive() }
        }
    }

    suspend fun drainOnce(api: NodeApi) = mutex.withLock {
        promoteAuto()
        for (a in dao.due(clock.now())) {
            when (a.state) {
                AttachmentState.UPLOADING -> upload(api, a)
                AttachmentState.QUEUED -> download(api, a)
            }
        }
        for (a in dao.acksPending()) ack(api, a)
    }

    private suspend fun promoteAuto() {
        val policy = settings.photoAutoDownload()
        val allowed = when (policy) {
            Settings.AUTO_ANY -> true
            Settings.AUTO_WIFI -> network.unmetered()
            else -> false
        }
        if (!allowed) return
        for (a in dao.autoRemote()) dao.update(a.copy(state = AttachmentState.QUEUED, nextAttemptMs = 0))
    }

    private suspend fun upload(api: NodeApi, a: AttachmentEntity) {
        val name = a.file ?: return
        val file = files.file(name)
        try {
            val st = api.blobCreate(Record.parseFrom(checkNotNull(a.metaRecord)))
            val done = AtomicInteger(st.chunks - st.missingCount)
            dao.progress(a.id, done.get())
            val sem = Semaphore(PARALLEL_PUTS)
            coroutineScope {
                st.missingList.map { idx ->
                    async {
                        sem.withPermit {
                            val data = withContext(Dispatchers.IO) { readChunk(file, idx) }
                            api.blobPut(a.blobKey, idx, data)
                            dao.progress(a.id, done.incrementAndGet())
                        }
                    }
                }.awaitAll()
            }
            db.withTransaction {
                dao.update(a.copy(state = AttachmentState.UPLOADED, doneChunks = st.chunks, attempts = 0))
                db.outboxDao().release(a.messageLocalId, clock.now())
            }
            outbox.kick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            if (e.status == Status.STATUS_QUOTA || e.status == Status.STATUS_INVALID ||
                (e.status == Status.STATUS_NOT_FOUND && a.doneChunks == 0)) {
                db.withTransaction {
                    dao.update(a.copy(state = AttachmentState.UPLOAD_FAILED))
                    db.messageDao().markFailed(a.messageLocalId)
                    db.outboxDao().deleteForMessage(a.messageLocalId)
                }
            } else backoff(a)
        } catch (e: Exception) {
            log.log(Level.INFO, "upload failed; retrying", e)
            backoff(a)
        }
    }

    private fun readChunk(file: java.io.File, idx: Int): ByteArray = RandomAccessFile(file, "r").use { f ->
        val off = idx.toLong() * BlobCipher.CHUNK
        val len = minOf(BlobCipher.CHUNK.toLong(), f.length() - off).toInt()
        ByteArray(len).also { f.seek(off); f.readFully(it) }
    }

    private suspend fun download(api: NodeApi, a: AttachmentEntity) {
        val name = a.file ?: files.newName()
        val part = files.part(name)
        val chunks = ((a.size + BlobCipher.CHUNK - 1) / BlobCipher.CHUNK).toInt()
        try {
            val start = withContext(Dispatchers.IO) {
                val whole = (part.takeIf { it.exists() }?.length() ?: 0) / BlobCipher.CHUNK
                RandomAccessFile(part, "rw").use { it.setLength(whole * BlobCipher.CHUNK) } // drop a torn last chunk
                whole.toInt()
            }
            dao.progress(a.id, start)
            for (idx in start until chunks) {
                val data = api.blobGet(a.blobKey, idx)
                val expected = minOf(BlobCipher.CHUNK.toLong(), a.size - idx.toLong() * BlobCipher.CHUNK).toInt()
                // C17: a chunk is an exact slice of the ciphertext; anything else is refused before it touches the disk.
                if (data.size != expected) throw IOException("blob chunk $idx: ${data.size} bytes, expected $expected")
                withContext(Dispatchers.IO) { part.appendBytes(data) }
                dao.progress(a.id, idx + 1)
            }
            val ok = withContext(Dispatchers.IO) { part.length() == a.size && BlobCipher.sha256(part).contentEquals(a.digest) }
            if (!ok) {
                part.delete()
                val attempts = a.attempts + 1
                dao.update(a.copy(file = name, doneChunks = 0, attempts = attempts,
                    state = if (attempts >= MAX_DIGEST_FAILURES) AttachmentState.FAILED else AttachmentState.QUEUED,
                    nextAttemptMs = clock.now() + 1000L * attempts))
                return
            }
            withContext(Dispatchers.IO) { check(part.renameTo(files.file(name))) { "rename failed" } }
            dao.update(a.copy(file = name, state = AttachmentState.DOWNLOADED, doneChunks = chunks, ackPending = true, attempts = 0))
            if (settings.photoSaveToGallery() && !gallery.needsPermission()) gallery.save(a.messageLocalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            if (e.status == Status.STATUS_NOT_FOUND || e.status == Status.STATUS_UNAUTHORIZED) {
                part.delete()
                dao.update(a.copy(state = AttachmentState.GONE))
            } else backoff(a)
        } catch (e: Exception) {
            log.log(Level.INFO, "download failed; retrying", e)
            backoff(a)
        }
    }

    private suspend fun ack(api: NodeApi, a: AttachmentEntity) {
        val me = accounts.require()
        val rec = me.sign(RecordCodec.body(RecordType.BLOB_FETCHED, me.userId,
            BlobFetched.newBuilder().setBlobKey(bs(a.blobKey)).build().toByteArray(), createdMs = clock.now()))
        try {
            api.blobAck(rec)
            dao.update(a.copy(ackPending = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            // a refused ack can't succeed later (the blob is gone or the record is invalid): stop trying
            if (e.status != Status.STATUS_RETRY_LATER) dao.update(a.copy(ackPending = false))
        } catch (e: Exception) {
            log.log(Level.FINE, "blob ack failed; next pass", e)
        }
    }

    private suspend fun backoff(a: AttachmentEntity) {
        val fresh = dao.get(a.id) ?: return
        val delay = minOf((1L shl minOf(fresh.attempts, 20)) * 1000, MAX_BACKOFF_MS)
        dao.update(fresh.copy(attempts = fresh.attempts + 1, nextAttemptMs = clock.now() + delay))
    }

    companion object {
        const val PARALLEL_PUTS = 4
        const val MAX_DIGEST_FAILURES = 3
        const val MAX_BACKOFF_MS = 300_000L
        private val log: Logger = Logger.getLogger("MediaTransfers")
    }
}
