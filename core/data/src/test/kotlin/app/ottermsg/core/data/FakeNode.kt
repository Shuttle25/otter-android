package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Ack
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.InviteBundle
import app.ottermsg.proto.v1.InviteUsed
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.NodeInfo
import app.ottermsg.proto.v1.OneTimePreKey
import app.ottermsg.proto.v1.PreKeyBundle
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.PrekeysLow
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBatch
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.RegisterRequest
import app.ottermsg.proto.v1.SignedPreKey
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun ByteString.hex() = toByteArray().hex()

/**
 * In-memory node with the same rules as the Go service for the M1 methods: invites, registration,
 * directory, prekeys (one-time keys consumed), mailboxes with per-session push and ACK.
 */
class FakeNode(private val clock: () -> Long) {
    val nodeId = ByteArray(16) { 0x42 }
    val network = Ed25519.keyPairFromSeed(ByteArray(32) { 9 })
    /** Signs a manifest; [withSelf] lists this node, [extraHosts] adds other (fake) nodes. */
    fun signManifest(version: Long, withSelf: Boolean = true, extraHosts: List<String> = emptyList()): Record {
        val b = Manifest.newBuilder().setVersion(version).setIssuedMs(version).setNetworkPub(bs(network.publicKey))
        if (withSelf) b.addNodes(NodeInfo.newBuilder().setNodeId(bs(nodeId)).setLabel("fake")
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost("fake").setPort(1).setPath("/fakepath")))
        extraHosts.forEachIndexed { i, h -> b.addNodes(NodeInfo.newBuilder().setNodeId(bs(ByteArray(16) { (i + 1).toByte() })).setLabel(h)
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost(h).setPort(1).setPath("/p"))) }
        return RecordCodec.sign(RecordCodec.body(RecordType.MANIFEST, Ids.id16(network.publicKey), b.build().toByteArray(), createdMs = version), network)
    }

    @Volatile var manifest: Record = signManifest(1)
    private var manifestVersion = 1L

    /** Like manifest.publish on a real node: becomes current and is pushed to every session. */
    fun publish(rec: Record, version: Long) {
        synchronized(lock) {
            manifest = rec; manifestVersion = version
            sessions.forEach { it.emit("manifest", app.ottermsg.proto.v1.ManifestResponse.newBuilder().setManifest(rec).build()) }
        }
    }

    private class InviteState(val inviter: ByteArray, val expiresMs: Long, var usedBy: ByteArray? = null)
    private class User(val record: Record, val entry: DirectoryEntry)
    private class Keys(val registrationId: Int, var signed: SignedPreKey, var kyber: SignedPreKey, val oneTime: ArrayDeque<OneTimePreKey>)

    private val lock = Any()
    private val invites = HashMap<String, InviteState>()
    private val users = LinkedHashMap<String, User>()
    private val keys = HashMap<String, Keys>()
    private val mailboxes = HashMap<String, LinkedHashMap<String, Record>>()
    private val applied = HashSet<String>()
    private val sessions = mutableListOf<Session>()

    /** When false every call fails with IOException (node unreachable). */
    @Volatile var reachable = true
    /** If set, msg.send fails with this status. */
    @Volatile var failSend: Status? = null
    /** If set, prekeys.fetch fails with this status. */
    @Volatile var failFetch: Status? = null
    /** One-time keys uploaded per owner node (hex), across registrations and refills. */
    val uploadedByOwner = HashMap<String, Int>()

    /** Simulates a node that lost the user's undelivered envelopes (records stay "applied" elsewhere: a fresh node). */
    fun forgetMailbox(user: ByteArray) = synchronized(lock) {
        mailboxes[user.hex()]?.keys?.forEach { applied.remove(it) }
        mailboxes.remove(user.hex())
    }
    var sends = 0; private set

    fun bootstrapLink(ttlMs: Long = 3_600_000): String {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        synchronized(lock) { invites[sha(secret).hex()] = InviteState(Ids.id16(network.publicKey), clock() + ttlMs) }
        return InviteLink.encode(InviteBundle.newBuilder().setNetworkPub(bs(network.publicKey)).setManifest(manifest).setSecret(bs(secret)).build())
    }

    fun mailbox(user: ByteArray): List<Record> = synchronized(lock) { mailboxes[user.hex()]?.values?.toList() ?: emptyList() }
    fun oneTimeLeft(user: ByteArray): Int = synchronized(lock) { keys[user.hex()]?.oneTime?.size ?: 0 }
    fun directoryRecords(): List<Record> = synchronized(lock) { users.values.map { it.record } }

    fun connect(): Session = Session().also { synchronized(lock) { sessions += it } }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun fail(s: Status, msg: String): Nothing = throw RpcException(s, msg)
    private fun body(r: Record) = RecordBody.parseFrom(r.body)
    private fun keyOf(b: RecordBody) = (b.author.toByteArray() + b.id.toByteArray())

    /** Verifies a record authored by a registered user (or by [pub] when given). */
    private fun verifyUser(r: Record, pub: ByteArray? = null): RecordBody {
        val b = body(r)
        val key = pub ?: users[b.author.hex()]?.entry?.accountPub?.toByteArray() ?: fail(Status.STATUS_UNAUTHORIZED, "unknown author")
        if (!RecordCodec.verify(r, key)) fail(Status.STATUS_INVALID, "bad signature")
        return b
    }

    inner class Session : NodeApi {
        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
        override val events: Flow<Event> = _events
        @Volatile var user: ByteArray? = null; private set
        private val pushed = HashSet<String>()
        @Volatile var closed = false

        private fun check() { if (!reachable || closed) throw IOException("node unreachable") }
        private fun me(): ByteArray = user ?: fail(Status.STATUS_UNAUTHORIZED, "login required")
        fun close() { closed = true; synchronized(lock) { sessions -= this } }
        internal fun emit(type: String, body: MessageLite) {
            _events.tryEmit(Event.newBuilder().setType(type).setBody(body.toByteString()).build())
        }

        /** Pushes mailbox entries not yet pushed in this session (like the Go gateway). Call with [lock] held. */
        internal fun deliver() {
            val u = user ?: return
            val box = mailboxes[u.hex()] ?: return
            pushed.retainAll(box.keys)
            val fresh = box.filterKeys { it !in pushed }
            if (fresh.isEmpty()) return
            pushed += fresh.keys
            emit("envelopes", RecordBatch.newBuilder().addAllRecords(fresh.values).build())
        }

        override suspend fun hello(): app.ottermsg.proto.v1.Hello = app.ottermsg.proto.v1.Hello.newBuilder().setProtocolVersion(1)
            .setNodeId(bs(nodeId)).setManifestVersion(manifestVersion).build()

        override suspend fun register(req: RegisterRequest) { check(); synchronized(lock) {
            if (req.inviteSecret.size() != 32) fail(Status.STATUS_INVALID, "incomplete registration")
            val inviteId = sha(req.inviteSecret.toByteArray())
            val inv = invites[inviteId.hex()] ?: fail(Status.STATUS_NOT_FOUND, "unknown invite")
            if (inv.usedBy != null) fail(Status.STATUS_CONFLICT, "invite already used")
            if (inv.expiresMs < clock()) fail(Status.STATUS_CONFLICT, "invite expired")
            val dirBody = body(req.directoryEntry)
            val entry = DirectoryEntry.parseFrom(dirBody.payload)
            verifyUser(req.directoryEntry, entry.accountPub.toByteArray())
            if (!entry.userId.toByteArray().contentEquals(Ids.id16(entry.accountPub.toByteArray()))) fail(Status.STATUS_INVALID, "author binding")
            if (users.containsKey(entry.userId.hex())) fail(Status.STATUS_CONFLICT, "already registered")
            if (users.values.any { it.entry.username == entry.username }) fail(Status.STATUS_CONFLICT, "username taken")
            val pk = PreKeys.parseFrom(verifyUser(req.prekeys, entry.accountPub.toByteArray()).payload)
            val used = InviteUsed.parseFrom(verifyUser(req.inviteUsed, entry.accountPub.toByteArray()).payload)
            if (!used.inviteId.toByteArray().contentEquals(inviteId)) fail(Status.STATUS_INVALID, "invite_used")
            inv.usedBy = entry.userId.toByteArray()
            users[entry.userId.hex()] = User(req.directoryEntry, entry)
            keys[entry.userId.hex()] = Keys(pk.registrationId, pk.signedPrekey, pk.kyberPrekey,
                ArrayDeque(pk.oneTimeList.filter { it.ownerNode.toByteArray().contentEquals(nodeId) }))
            sessions.filter { it.user != null }.forEach { it.emit("directory", app.ottermsg.proto.v1.Empty.getDefaultInstance()) }
        } }

        override suspend fun login(userId: ByteArray, account: Ed25519.KeyPair) { check(); synchronized(lock) {
            val u = users[userId.hex()] ?: fail(Status.STATUS_UNAUTHORIZED, "unknown user")
            if (!u.entry.accountPub.toByteArray().contentEquals(account.publicKey)) fail(Status.STATUS_UNAUTHORIZED, "bad signature")
            if (user != null) fail(Status.STATUS_INVALID, "already logged in")
            user = userId
            deliver()
        } }

        override suspend fun manifest(): Record { check(); return manifest }
        override suspend fun directory(): List<Record> { check(); me(); return directoryRecords() }

        override suspend fun uploadPrekeys(record: Record) { check(); synchronized(lock) {
            val b = verifyUser(record)
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val pk = PreKeys.parseFrom(b.payload)
            pk.oneTimeList.forEach { uploadedByOwner.merge(it.ownerNode.hex(), 1, Int::plus) }
            keys[b.author.hex()]!!.oneTime.addAll(pk.oneTimeList.filter { it.ownerNode.toByteArray().contentEquals(nodeId) })
        } }

        override suspend fun fetchPrekeys(userId: ByteArray, device: Int): PreKeyBundle { check(); me(); failFetch?.let { fail(it, "fetch refused") }; return synchronized(lock) {
            val u = users[userId.hex()] ?: fail(Status.STATUS_NOT_FOUND, "user")
            val k = keys[userId.hex()] ?: fail(Status.STATUS_NOT_FOUND, "prekeys")
            val ot = k.oneTime.removeFirstOrNull()
            if (k.oneTime.size < 10) sessions.filter { it.user?.contentEquals(userId) == true }
                .forEach { it.emit("prekeys_low", PrekeysLow.newBuilder().setRemaining(k.oneTime.size).build()) }
            PreKeyBundle.newBuilder().setUserId(bs(userId)).setDeviceId(1).setRegistrationId(k.registrationId)
                .setIdentityPub(u.entry.identityPub).setSignedPrekey(k.signed).setKyberPrekey(k.kyber)
                .apply { if (ot != null) setOneTime(ot) }.build()
        } }

        override suspend fun send(envelope: Record) { check(); synchronized(lock) {
            failSend?.let { fail(it, "send refused") }
            val b = verifyUser(envelope)
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val env = Envelope.parseFrom(b.payload)
            // Same rule as the Go validator: SENDER_KEY ⇔ one shared ciphertext and no per-recipient content.
            val group = env.kind == Envelope.Kind.SENDER_KEY
            if (env.recipientsCount == 0 || group != !env.sharedContent.isEmpty ||
                env.recipientsList.any { group != it.content.isEmpty }) fail(Status.STATUS_INVALID, "envelope shape")
            env.recipientsList.forEach { r -> if (!users.containsKey(r.user.hex())) fail(Status.STATUS_NOT_FOUND, "recipient") }
            sends++
            val key = keyOf(b).hex()
            if (!applied.add(key)) return // idempotent resend
            env.recipientsList.forEach { r -> mailboxes.getOrPut(r.user.hex()) { LinkedHashMap() }[key] = envelope }
            sessions.forEach { s -> if (env.recipientsList.any { s.user?.contentEquals(it.user.toByteArray()) == true }) s.deliver() }
        } }

        override suspend fun ack(ack: Record) { check(); synchronized(lock) {
            val b = verifyUser(ack)
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val a = Ack.parseFrom(b.payload)
            val box = mailboxes[b.author.hex()] ?: return
            a.envelopeIdsList.forEach { box.remove(it.hex()) }
        } }

        override suspend fun createInvite(invite: Record) { check(); synchronized(lock) {
            val b = verifyUser(invite)
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val i = Invite.parseFrom(b.payload)
            invites[i.inviteId.hex()] = InviteState(b.author.toByteArray(), i.expiresMs)
        } }

        override suspend fun ping() { check() }
    }
}
