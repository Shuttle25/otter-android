package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Ack
import app.ottermsg.proto.v1.BackupAuthResponse
import app.ottermsg.proto.v1.BackupFetchResponse
import app.ottermsg.proto.v1.BackupMeta
import app.ottermsg.proto.v1.BlobMeta
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.InviteBundle
import app.ottermsg.proto.v1.InviteUsed
import app.ottermsg.proto.v1.LoginResponse
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
class FakeNode(private val clock: () -> Long, val nodeId: ByteArray = ByteArray(16) { 0x42 }) {
    companion object { const val APK_CHUNK = 1 shl 20 }
    /**
     * Answers auth.login and every logged-in request with STATUS_REVOKED (served by [FakeNodeTransport]), like a node
     * whose account was disabled by `otter-admin user revoke` — or a hostile node lying it.
     */
    @Volatile var answerRevoked = false
    val network = Ed25519.keyPairFromSeed(ByteArray(32) { 9 })
    /** The current manifest's sig_v1_until_ms (D109): set by [publish]; the node verifies and signs under it. */
    @Volatile var sigV1UntilMs = 0L; private set
    /** This node's record signature context (D109), like the Go node's Validator and ContextFor. */
    val ctx: SigContext get() = SigContext(Ids.id16(network.publicKey), sigV1UntilMs)
    /**
     * Signs a manifest; [withSelf] lists this node, [extraHosts] adds other (fake) nodes. A manifest with a cut-over
     * ([sigV1UntilMs] != 0) is v2-signed, like `otter-admin manifest sign --sig-v1-until`.
     */
    fun signManifest(version: Long, withSelf: Boolean = true, extraHosts: List<String> = emptyList(),
                     newsKeys: List<ByteArray> = emptyList(), foundingMembers: List<ByteArray> = emptyList(),
                     sigV1UntilMs: Long = 0): Record {
        val b = Manifest.newBuilder().setVersion(version).setIssuedMs(version).setNetworkPub(bs(network.publicKey))
            .setSigV1UntilMs(sigV1UntilMs)
        newsKeys.forEach { b.addNewsKeys(bs(it)) }
        foundingMembers.forEach { b.addFoundingMembers(bs(it)) }
        if (withSelf) b.addNodes(NodeInfo.newBuilder().setNodeId(bs(nodeId)).setLabel("fake")
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost("fake").setPort(1).setPath("/fakepath")))
        extraHosts.forEachIndexed { i, h -> b.addNodes(NodeInfo.newBuilder().setNodeId(bs(ByteArray(16) { (i + 1).toByte() })).setLabel(h)
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost(h).setPort(1).setPath("/p"))) }
        return RecordCodec.sign(RecordCodec.body(RecordType.MANIFEST, Ids.id16(network.publicKey), b.build().toByteArray(), createdMs = version), network,
            SigContext(Ids.id16(network.publicKey), sigV1UntilMs))
    }

    @Volatile var manifest: Record = signManifest(1)
    private var manifestVersion = 1L

    /** Like manifest.publish on a real node: becomes current and is pushed to every session. */
    fun publish(rec: Record, version: Long) {
        synchronized(lock) {
            manifest = rec; manifestVersion = version
            sigV1UntilMs = Manifest.parseFrom(body(rec).payload).sigV1UntilMs
            sessions.forEach { it.emit("manifest", app.ottermsg.proto.v1.ManifestResponse.newBuilder().setManifest(rec).build()) }
        }
    }

    // ---- news (D66–D68) ----
    private val newsRecords = mutableListOf<Record>()
    private val apks = HashMap<String, ByteArray>()

    /** Like news.publish on a real node (no validation here): stored and a `news` event to every logged-in session. */
    fun publishNews(rec: Record) = synchronized(lock) {
        newsRecords += rec
        sessions.filter { it.user != null }.forEach { it.emit("news", app.ottermsg.proto.v1.Empty.getDefaultInstance()) }
    }

    /** Like `otter-node apk import`. */
    fun importApk(data: ByteArray) = synchronized(lock) { apks[sha(data).hex()] = data }

    private class InviteState(val inviter: ByteArray, val expiresMs: Long, var usedBy: ByteArray? = null)
    private class User(val record: Record, val entry: DirectoryEntry)
    private class Keys(var registrationId: Int, var signed: SignedPreKey, var kyber: SignedPreKey, val oneTime: ArrayDeque<OneTimePreKey>,
                       var installedMs: Long = 0)

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
    /** Current push token per user (hex), from push.register (D60). */
    val pushTokens = mutableMapOf<String, String>()
    /** The last PUSH_TOKEN payload per user (hex), with the D94 flags (messages_off, withdrawn). */
    val pushRecords = mutableMapOf<String, app.ottermsg.proto.v1.PushToken>()
    /** Served by turn.credentials (D77); null = TURN off on this node (STATUS_UNAVAILABLE). */
    @Volatile var turn: app.ottermsg.proto.v1.TurnCredentials? = null
    /** push.status (D79): whether a non-empty push token is registered for [user]. */
    fun hasPush(user: ByteArray): Boolean = synchronized(lock) { !pushTokens[user.hex()].isNullOrEmpty() }

    /** Like `otter-admin invite`: a network-signed INVITE with its redeem key travels in the link (D100). */
    fun bootstrapLink(ttlMs: Long = 3_600_000): String {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val inv = Invite.newBuilder().setInviteId(bs(sha(secret))).setExpiresMs(clock() + ttlMs)
            .setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey)).build()
        val rec = RecordCodec.sign(RecordCodec.body(RecordType.INVITE, Ids.id16(network.publicKey), inv.toByteArray(),
            expiresMs = clock() + ttlMs, createdMs = clock()), network, ctx)
        synchronized(lock) { invites[sha(secret).hex()] = InviteState(Ids.id16(network.publicKey), clock() + ttlMs) }
        return InviteLink.encode(InviteBundle.newBuilder().setNetworkPub(bs(network.publicKey)).setManifest(manifest)
            .setSecret(bs(secret)).setInvite(rec).build())
    }

    /** Sees every register request (the node side of what the phone sends). */
    @Volatile var onRegister: (RegisterRequest) -> Unit = {}
    /** register calls that reached this node. */
    @Volatile var registrations = 0; private set
    /** Users whose invite rights the admin withdrew (INVITE_RIGHTS no): invite.create → UNAUTHORIZED. */
    val noInviteRights = HashSet<String>()
    fun withdrawInviteRights(user: ByteArray) = synchronized(lock) { noInviteRights += user.hex() }

    fun mailbox(user: ByteArray): List<Record> = synchronized(lock) { mailboxes[user.hex()]?.values?.toList() ?: emptyList() }
    fun oneTimeLeft(user: ByteArray): Int = synchronized(lock) { keys[user.hex()]?.oneTime?.size ?: 0 }
    /** Network-signed REVOCATION / INVITE_RIGHTS records served by directory.sync (D99). */
    private val membership = mutableListOf<Record>()
    /** Like `otter-admin user revoke`; a [signer] other than the network key plays a forger. */
    fun revoke(user: ByteArray, signer: Ed25519.KeyPair = network, createdMs: Long = clock(), reason: String = "") = synchronized(lock) {
        membership += RecordCodec.sign(RecordCodec.body(RecordType.REVOCATION, Ids.id16(network.publicKey),
            app.ottermsg.proto.v1.Revocation.newBuilder().setUserId(bs(user)).setReason(reason).build().toByteArray(), createdMs = createdMs), signer, ctx)
    }
    /**
     * Like `otter-admin user rekey --new` (D111): a network-signed REKEY old → new, served by directory.sync. A [signer]
     * or [author] other than the network's plays a forger (the old account, a node key).
     */
    fun rekey(old: ByteArray, new: ByteArray, signer: Ed25519.KeyPair = network, createdMs: Long = clock(),
              author: ByteArray = Ids.id16(network.publicKey), sig: SigContext = ctx): Record = synchronized(lock) {
        RecordCodec.sign(RecordCodec.body(RecordType.REKEY, author, app.ottermsg.proto.v1.Rekey.newBuilder().setOldUserId(bs(old))
            .setNewUserId(bs(new)).build().toByteArray(), createdMs = createdMs), signer, sig).also { membership += it }
    }
    /** Like `otter-admin user invite-rights`: a network-signed INVITE_RIGHTS record. */
    fun inviteRights(user: ByteArray, canInvite: Boolean, version: Long, createdMs: Long = clock(), signer: Ed25519.KeyPair = network) =
        synchronized(lock) {
            membership += RecordCodec.sign(RecordCodec.body(RecordType.INVITE_RIGHTS, Ids.id16(network.publicKey),
                app.ottermsg.proto.v1.InviteRights.newBuilder().setUserId(bs(user)).setCanInvite(canInvite).setVersion(version).build()
                    .toByteArray(), createdMs = createdMs), signer, ctx)
        }
    fun directoryRecords(): List<Record> = synchronized(lock) { users.values.map { it.record } + membership }
    private val directoryUpdateCount = HashMap<String, Int>()
    /** Accepted directory.update calls of [user]. */
    fun directoryUpdates(user: ByteArray): Int = synchronized(lock) { directoryUpdateCount[user.hex()] ?: 0 }
    // ---- blobs (D44/D45): same rules as the Go service, chunks kept in memory ----
    private class FakeBlob(val meta: BlobMeta, val author: String, val record: Record, val chunks: MutableMap<Int, ByteArray> = HashMap(),
                           val fetched: MutableSet<String> = HashSet())
    private val blobs = HashMap<String, FakeBlob>()
    private val goneBlobs = HashSet<String>()
    /** If set, blob.create and blob.put fail with this status (e.g. QUOTA). */
    @Volatile var failBlobPut: Status? = null
    /** If set, blob.get fails with this status. */
    @Volatile var failBlobGet: Status? = null
    /** Delay per blob.get (lets tests observe download progress). */
    @Volatile var blobGetDelayMs = 0L
    var blobPuts = 0; private set
    fun blobChunks(key: ByteArray): Int = synchronized(lock) { blobs[key.hex()]?.chunks?.size ?: 0 }
    fun blobGone(key: ByteArray): Boolean = synchronized(lock) { key.hex() in goneBlobs }
    fun blobReaders(key: ByteArray): Int = synchronized(lock) { blobs[key.hex()]?.meta?.readersCount ?: 0 }
    fun blobKeys(): List<String> = synchronized(lock) { blobs.keys.toList() }

    /** Replaces a user's entry without the update rules (tests: an entry published elsewhere). */
    fun putDirectoryEntry(rec: Record) = synchronized(lock) {
        val e = DirectoryEntry.parseFrom(body(rec).payload)
        users[e.userId.hex()] = User(rec, e)
    }

    /**
     * A member's DIRECTORY_ENTRY invited by the network key (a valid chain), signed under [sig]: a hostile node can
     * serve one signed for another network (D109).
     */
    fun directoryEntryFor(seed: Byte, name: String, sig: SigContext = ctx): Record {
        val acc = app.ottermsg.core.crypto.SeedKeys.accountKeyPair(ByteArray(32) { seed })
        val id = app.ottermsg.core.signal.IdentityKeys.fromSeed(ByteArray(32) { seed })
        val uid = Ids.id16(acc.publicKey)
        val secret = ByteArray(32) { (seed + 100).toByte() }
        val now = clock()
        val inv = RecordCodec.sign(RecordCodec.body(RecordType.INVITE, Ids.id16(network.publicKey), Invite.newBuilder()
            .setInviteId(bs(sha(secret))).setExpiresMs(now + 3_600_000).setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey))
            .build().toByteArray(), createdMs = now), network, ctx)
        val e = DirectoryEntry.newBuilder().setUserId(bs(uid)).setUsername(name).setDisplayName(name.uppercase())
            .setAccountPub(bs(acc.publicKey)).setIdentityPub(bs(id.publicKey.serialize()))
            .setIdentitySig(bs(app.ottermsg.core.signal.IdentityKeys.bindAccount(id, acc.publicKey))).setVersion(1).setRegisteredMs(now + 1)
            .setInviteRecord(inv.toByteString())
            .setInviteProof(bs(InviteKeys.redeemKey(secret).sign(InviteKeys.redeemMessage(sha(secret), uid, acc.publicKey)))).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, uid, e.toByteArray(), createdMs = now + 1), acc, sig)
    }

    fun connect(): Session = Session().also { synchronized(lock) { sessions += it } }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun fail(s: Status, msg: String): Nothing = throw RpcException(s, msg)
    private fun body(r: Record) = RecordBody.parseFrom(r.body)
    private fun keyOf(b: RecordBody) = (b.author.toByteArray() + b.id.toByteArray())

    /** Verifies a record authored by a registered user (or by [pub] when given). */
    private fun verifyUser(r: Record, pub: ByteArray? = null): RecordBody {
        val b = body(r)
        val key = pub ?: users[b.author.hex()]?.entry?.accountPub?.toByteArray() ?: fail(Status.STATUS_UNAUTHORIZED, "unknown author")
        if (!RecordCodec.verify(r, key, ctx)) fail(Status.STATUS_INVALID, "bad signature")
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
            registrations++
            onRegister(req)
            if (!req.hasDirectoryEntry() || !req.hasPrekeys() || !req.hasInviteUsed()) fail(Status.STATUS_INVALID, "incomplete registration")
            val dirBody = body(req.directoryEntry)
            val entry = DirectoryEntry.parseFrom(dirBody.payload)
            verifyUser(req.directoryEntry, entry.accountPub.toByteArray())
            // Like Service.admitChain (D100): the INVITE travels in the entry, the proof replaces the secret.
            if (entry.inviteRecord.isEmpty || entry.inviteProof.size() != 64) fail(Status.STATUS_INVALID, "no invite chain (update the app)")
            val invRec = Record.parseFrom(entry.inviteRecord)
            val invBody = body(invRec)
            if (invBody.type != RecordType.INVITE) fail(Status.STATUS_INVALID, "malformed invite record")
            val invMsg = Invite.parseFrom(invBody.payload)
            if (invMsg.inviteId.size() != 32 || invMsg.redeemPub.size() != 32) fail(Status.STATUS_INVALID, "malformed invite record")
            val signer = if (invBody.author.toByteArray().contentEquals(Ids.id16(network.publicKey))) network.publicKey
                else users[invBody.author.hex()]?.entry?.accountPub?.toByteArray() ?: fail(Status.STATUS_RETRY_LATER, "inviter not known here yet")
            if (!RecordCodec.verify(invRec, signer, ctx)) fail(Status.STATUS_INVALID, "invite signature")
            val proofOk = runCatching { Ed25519.verify(invMsg.redeemPub.toByteArray(), InviteKeys.redeemMessage(invMsg.inviteId.toByteArray(),
                entry.userId.toByteArray(), entry.accountPub.toByteArray()), entry.inviteProof.toByteArray()) }.getOrDefault(false)
            if (!proofOk) fail(Status.STATUS_INVALID, "invite proof")
            val inviteId = invMsg.inviteId.toByteArray()
            val inv = invites.getOrPut(inviteId.hex()) { InviteState(invBody.author.toByteArray(), invMsg.expiresMs) }
            if (inv.usedBy != null) fail(Status.STATUS_CONFLICT, "invite already used")
            if (inv.expiresMs < clock()) fail(Status.STATUS_CONFLICT, "invite expired")
            if (!app.ottermsg.core.data.directory.Membership.inviteLifetimeOk(invBody.createdMs, invMsg.expiresMs))
                fail(Status.STATUS_INVALID, "invite lifetime over 30 days")
            if (entry.registeredMs < invBody.createdMs || entry.registeredMs > invMsg.expiresMs)
                fail(Status.STATUS_INVALID, "registration outside the invite's lifetime")
            if (!entry.userId.toByteArray().contentEquals(Ids.id16(entry.accountPub.toByteArray()))) fail(Status.STATUS_INVALID, "author binding")
            if (users.containsKey(entry.userId.hex())) fail(Status.STATUS_CONFLICT, "already registered")
            if (users.values.any { it.entry.username == entry.username }) fail(Status.STATUS_CONFLICT, "username taken")
            val pk = PreKeys.parseFrom(verifyUser(req.prekeys, entry.accountPub.toByteArray()).payload)
            val used = InviteUsed.parseFrom(verifyUser(req.inviteUsed, entry.accountPub.toByteArray()).payload)
            if (!used.inviteId.toByteArray().contentEquals(inviteId)) fail(Status.STATUS_INVALID, "invite_used")
            inv.usedBy = entry.userId.toByteArray()
            users[entry.userId.hex()] = User(req.directoryEntry, entry)
            keys[entry.userId.hex()] = Keys(pk.registrationId, pk.signedPrekey, pk.kyberPrekey,
                ArrayDeque(pk.oneTimeList.filter { it.ownerNode.toByteArray().contentEquals(nodeId) }), pk.installedMs)
            sessions.filter { it.user != null }.forEach { it.emit("directory", app.ottermsg.proto.v1.Empty.getDefaultInstance()) }
        } }

        override suspend fun login(userId: ByteArray, account: Ed25519.KeyPair): LoginResponse { check(); synchronized(lock) {
            val u = users[userId.hex()] ?: fail(Status.STATUS_UNAUTHORIZED, "unknown user")
            if (!u.entry.accountPub.toByteArray().contentEquals(account.publicKey)) fail(Status.STATUS_UNAUTHORIZED, "bad signature")
            if (user != null) fail(Status.STATUS_INVALID, "already logged in")
            user = userId
            deliver()
            val k = keys[userId.hex()]
            val b = LoginResponse.newBuilder().setRegistrationId(k?.registrationId ?: 0).setInstalledMs(lieInstalledMs ?: k?.installedMs ?: 0)
            (fakeInstall ?: installs[userId.hex()]?.first)?.let { b.setInstall(it) }
            return b.build()
        } }

        override suspend fun publishInstall(record: Record) { check(); if (refuseInstall) fail(Status.STATUS_INVALID, "unknown method"); synchronized(lock) {
            val b = verifyUser(record)
            if (b.type != RecordType.INSTALL || !b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_INVALID, "install")
            val i = app.ottermsg.proto.v1.Install.parseFrom(b.payload)
            val cur = installs[b.author.hex()]?.second
            if (cur != null && (i.installedMs < cur.installedMs || (i.installedMs == cur.installedMs && i.registrationId <= cur.registrationId))) return
            installs[b.author.hex()] = record to i
            val k = keys[b.author.hex()] ?: return
            if (i.installedMs > k.installedMs) { k.oneTime.clear(); k.installedMs = i.installedMs }
            if (i.installedMs >= k.installedMs) k.registrationId = i.registrationId
        } }

        override suspend fun manifest(): Record { check(); return manifest }
        override suspend fun directory(): List<Record> { check(); me(); val d = directoryRecords(); return directoryAnswer?.invoke(d) ?: d }

        override suspend fun uploadPrekeys(record: Record) { check(); synchronized(lock) {
            val b = verifyUser(record)
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val pk = PreKeys.parseFrom(b.payload)
            pk.oneTimeList.forEach { uploadedByOwner.merge(it.ownerNode.hex(), 1, Int::plus) }
            val k = keys[b.author.hex()]!!
            // D52: the newest install wins; an older one (a replaced phone) is ignored; the same one merges
            if (pk.installedMs < k.installedMs) return
            if (pk.installedMs > k.installedMs) {
                k.oneTime.clear(); k.installedMs = pk.installedMs
            }
            k.registrationId = pk.registrationId
            if (pk.hasSignedPrekey()) k.signed = pk.signedPrekey
            if (pk.hasKyberPrekey()) k.kyber = pk.kyberPrekey
            k.oneTime.addAll(pk.oneTimeList.filter { it.ownerNode.toByteArray().contentEquals(nodeId) })
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
            if (i.redeemPub.size() != 32) fail(Status.STATUS_INVALID, "invite without a redeem key (update the app)")
            if (!app.ottermsg.core.data.directory.Membership.inviteLifetimeOk(b.createdMs, i.expiresMs)) fail(Status.STATUS_INVALID, "bad payload")
            if (b.author.hex() in noInviteRights) fail(Status.STATUS_UNAUTHORIZED, "inviter may not invite")
            invites[i.inviteId.hex()] = InviteState(b.author.toByteArray(), i.expiresMs)
        } }

        override suspend fun updateDirectory(entry: Record) { check(); synchronized(lock) {
            val b = body(entry)
            if (b.type != RecordType.DIRECTORY_ENTRY) fail(Status.STATUS_INVALID, "not a directory entry")
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "entry of another user")
            val cur = users[b.author.hex()] ?: fail(Status.STATUS_RETRY_LATER, "user")
            verifyUser(entry)
            val e = DirectoryEntry.parseFrom(b.payload)
            val n = e.displayName.codePointCount(0, e.displayName.length)
            if (n < 1 || n > 64) fail(Status.STATUS_INVALID, "display name")
            if (e.username != cur.entry.username) fail(Status.STATUS_INVALID, "username changes are not supported")
            if (e.version <= cur.entry.version) fail(Status.STATUS_CONFLICT, "not newer")
            users[b.author.hex()] = User(entry, e)
            directoryUpdateCount.merge(b.author.hex(), 1, Int::plus)
            sessions.filter { it.user != null }.forEach { it.emit("directory", app.ottermsg.proto.v1.Empty.getDefaultInstance()) }
        } }

        override suspend fun blobCreate(meta: Record): app.ottermsg.proto.v1.BlobStatus { check(); synchronized(lock) {
            failBlobPut?.let { fail(it, "blob refused") }
            val b = verifyUser(meta)
            if (b.type != RecordType.BLOB_META) fail(Status.STATUS_INVALID, "not a blob meta")
            if (!b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "author")
            val m = BlobMeta.parseFrom(b.payload)
            val n = ((m.size + m.chunkSize - 1) / m.chunkSize).toInt()
            val shapeOk = if (m.persistent) m.readersCount == 0 && b.expiresMs == 0L else m.readersCount > 0 && b.expiresMs != 0L
            if (m.chunkSize != 262144 || m.chunkSha256Count != n || !shapeOk) fail(Status.STATUS_INVALID, "meta")
            if (m.persistent && blobs.values.count { it.meta.persistent && it.author == b.author.hex() } >= 3)
                fail(Status.STATUS_QUOTA, "too many backup blobs")
            val key = keyOf(b).hex()
            if (key in goneBlobs) fail(Status.STATUS_NOT_FOUND, "deleted")
            val blob = blobs.getOrPut(key) { FakeBlob(m, b.author.hex(), meta) }
            return app.ottermsg.proto.v1.BlobStatus.newBuilder().setChunks(n)
                .addAllMissing((0 until n).filter { it !in blob.chunks }).build()
        } }

        override suspend fun blobPut(key: ByteArray, index: Int, data: ByteArray) { check(); synchronized(lock) {
            failBlobPut?.let { fail(it, "blob refused") }
            if (!key.copyOf(16).contentEquals(me())) fail(Status.STATUS_UNAUTHORIZED, "only the author uploads")
            val blob = blobs[key.hex()] ?: fail(Status.STATUS_NOT_FOUND, "unknown blob")
            val m = blob.meta
            val expected = minOf(m.chunkSize.toLong(), m.size - index.toLong() * m.chunkSize)
            if (index < 0 || index >= m.chunkSha256Count || data.size.toLong() != expected ||
                !sha(data).contentEquals(m.getChunkSha256(index).toByteArray())) fail(Status.STATUS_INVALID, "chunk")
            blob.chunks[index] = data
            blobPuts++
        } }

        override suspend fun blobGet(key: ByteArray, index: Int): ByteArray {
            check()
            if (blobGetDelayMs > 0) kotlinx.coroutines.delay(blobGetDelayMs)
            synchronized(lock) {
                failBlobGet?.let { fail(it, "get refused") }
                val granted = user == null && grantBlob?.contentEquals(key) == true
                val u = if (granted) key.copyOf(16).hex() else me().hex()
                if (key.hex() in goneBlobs) fail(Status.STATUS_NOT_FOUND, "deleted")
                val blob = blobs[key.hex()] ?: fail(Status.STATUS_RETRY_LATER, "not here yet")
                if (blob.author != u && blob.meta.readersList.none { it.hex() == u }) fail(Status.STATUS_UNAUTHORIZED, "not a reader")
                return blob.chunks[index] ?: fail(Status.STATUS_RETRY_LATER, "chunk not here yet")
            }
        }

        override suspend fun blobAck(record: Record) { check(); synchronized(lock) {
            val b = verifyUser(record)
            if (b.type != RecordType.BLOB_FETCHED || !b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_INVALID, "ack")
            val key = app.ottermsg.proto.v1.BlobFetched.parseFrom(b.payload).blobKey.hex()
            val blob = blobs[key] ?: return
            if (blob.meta.persistent) return
            if (blob.meta.readersList.any { it.hex() == b.author.hex() }) blob.fetched += b.author.hex()
            if (blob.meta.readersList.all { it.hex() in blob.fetched }) { blobs.remove(key); goneBlobs += key }
        } }

        override suspend fun ping() { check() }

        override suspend fun newsList(): List<Record> { check(); me(); return synchronized(lock) { newsRecords.toList() } }

        override suspend fun apkGet(sha256: ByteArray, index: Int): app.ottermsg.proto.v1.ApkChunk { check(); me()
            val data = synchronized(lock) { apks[sha256.hex()] } ?: fail(Status.STATUS_NOT_FOUND, "unknown apk")
            val from = index.toLong() * APK_CHUNK
            if (index < 0 || from >= data.size) fail(Status.STATUS_NOT_FOUND, "index past the end")
            val chunk = data.copyOfRange(from.toInt(), minOf(data.size.toLong(), from + APK_CHUNK).toInt())
            return app.ottermsg.proto.v1.ApkChunk.newBuilder().setData(bs(chunk)).setTotalSize(data.size.toLong()).build()
        }

        override suspend fun registerPush(record: Record) { check(); synchronized(lock) {
            val b = verifyUser(record)
            if (b.type != RecordType.PUSH_TOKEN || !b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_INVALID, "push token")
            val p = app.ottermsg.proto.v1.PushToken.parseFrom(b.payload)
            pushTokens[b.author.hex()] = p.token // "" when withdrawn: hasPush() is false, like a 0.9.0 node
            pushRecords[b.author.hex()] = p
        } }

        override suspend fun turnCredentials(): app.ottermsg.proto.v1.TurnCredentials { check(); me()
            return turn ?: fail(Status.STATUS_UNAVAILABLE, "turn off")
        }

        override suspend fun pushStatus(userId: ByteArray): Boolean { check(); me(); return hasPush(userId) }

        /** Set by a successful backup.fetch on a connection that is not logged in (D51). */
        @Volatile var grantBlob: ByteArray? = null; private set

        override suspend fun backupAuth(username: String): BackupAuthResponse { check(); synchronized(lock) {
            backupAuthNames += username
            clashCandidates(username)?.let { return (authAnswer ?: { a -> a })(BackupAuthResponse.newBuilder().addAllCandidates(it).build()) }
            val m = backupByName(username)?.second
            val answer = if (m != null) BackupAuthResponse.newBuilder().setSalt(m.salt).setKdfMKib(m.kdfMKib).setKdfT(m.kdfT).setKdfP(m.kdfP).build()
            else BackupAuthResponse.newBuilder().setSalt(bs(fakeSalt(username))).setKdfMKib(65536).setKdfT(3).setKdfP(1).build()
            return (authAnswer ?: { it })(answer)
        } }

        override suspend fun backupFetch(username: String, authSecret: ByteArray): BackupFetchResponse { check(); synchronized(lock) {
            backupFetches++
            if (clashCandidates(username) != null) fail(Status.STATUS_CONFLICT, "username is shared, use name#tag")
            val now = clock()
            val recent = authFailures.getOrPut(username) { mutableListOf() }.apply { removeAll { it < now - 3_600_000 } }
            if (recent.size >= 5) fail(Status.STATUS_RETRY_LATER, "too many attempts")
            val cur = backupByName(username)
            if (cur == null || cur.second.version == 0L || !sha(authSecret).contentEquals(cur.second.authVerifier.toByteArray())) {
                recent += now
                fail(Status.STATUS_UNAUTHORIZED, "wrong username or password")
            }
            val blobKey = cur.second.blobKey.toByteArray()
            val blob = blobs[blobKey.hex()] ?: fail(Status.STATUS_RETRY_LATER, "backup not replicated here yet")
            grantBlob = blobKey
            return (fetchAnswer ?: { it })(BackupFetchResponse.newBuilder().setBackupMeta(cur.first).setBlobMeta(blob.record).build())
        } }

        override suspend fun backupPublish(meta: Record) { check(); synchronized(lock) {
            val b = verifyUser(meta)
            if (b.type != RecordType.BACKUP_META || !b.author.toByteArray().contentEquals(me())) fail(Status.STATUS_INVALID, "not the author's backup meta")
            val m = BackupMeta.parseFrom(b.payload)
            if (m.version == 0L) { // decoy (C20): no blob
                if (refuseDecoy) fail(Status.STATUS_INVALID, "backup meta version") // a 0.8.x node
                if (m.size != 0L || !m.blobKey.toByteArray().contentEquals(b.author.toByteArray() + ByteArray(16))) fail(Status.STATUS_INVALID, "decoy")
            } else {
                val blob = blobs[m.blobKey.hex()] ?: fail(Status.STATUS_INVALID, "backup blob unknown here")
                if (blob.author != b.author.hex() || !blob.meta.persistent) fail(Status.STATUS_INVALID, "not the author's persistent blob")
            }
            val cur = backups[b.author.hex()]
            if (cur != null && cur.second.version >= m.version) return // latest version wins (D50)
            backups[b.author.hex()] = meta to m
            if (cur != null && cur.second.version > 0 && cur.second.blobKey != m.blobKey) {
                blobs.remove(cur.second.blobKey.hex()); goneBlobs += cur.second.blobKey.hex()
            }
        } }
    }

    // ---- backups (D50–D52) ----
    private val backups = HashMap<String, Pair<Record, BackupMeta>>()

    // ---- installs (C14): same rule as the Go node ----
    private val installs = HashMap<String, Pair<Record, app.ottermsg.proto.v1.Install>>()
    /** Hostile node: an unsigned installed_ms in every login answer. */
    @Volatile var lieInstalledMs: Long? = null
    /** Hostile node: this record in every login answer instead of the stored INSTALL. */
    @Volatile var fakeInstall: Record? = null
    /** A 0.8.x node: install.publish is an unknown method. */
    @Volatile var refuseInstall = false
    /** A 0.8.x node: a decoy BACKUP_META (version 0) is INVALID. */
    @Volatile var refuseDecoy = false
    fun installOf(user: ByteArray): app.ottermsg.proto.v1.Install? = synchronized(lock) { installs[user.hex()]?.second }

    // ---- hostile node (M9.1): answers rewritten before they leave the node ----
    /** Rewrites every backup.auth answer. */
    @Volatile var authAnswer: ((BackupAuthResponse) -> BackupAuthResponse)? = null
    /** Rewrites every backup.fetch answer. */
    @Volatile var fetchAnswer: ((BackupFetchResponse) -> BackupFetchResponse)? = null
    /** Rewrites every directory answer. */
    @Volatile var directoryAnswer: ((List<Record>) -> List<Record>)? = null
    /** backup.fetch calls received (an auth_secret left the phone). */
    @Volatile var backupFetches = 0; private set
    private val authFailures = HashMap<String, MutableList<Long>>()
    private val fakeKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private fun fakeSalt(username: String): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(fakeKey, "HmacSHA256")) }
            .doFinal(username.toByteArray()).copyOf(16)
    private fun backupByName(username: String): Pair<Record, BackupMeta>? {
        val base = username.substringBefore('#')
        val tag = if ('#' in username) username.substringAfter('#').lowercase() else null
        // mirror of Store.ResolveUsername: "name#tag" is the account named name whose id starts with tag
        val id = users.values.firstOrNull { it.entry.username == base && (tag == null || it.entry.userId.hex().take(8) == tag) }
            ?.entry?.userId?.hex() ?: return null
        return backups[id]
    }
    /** D103: names that clashed (a partition); a plain one answers with candidates, its fetch is CONFLICT. */
    private val clashed = mutableSetOf<String>()
    fun clash(name: String) = synchronized(lock) { clashed += name }
    private fun clashCandidates(username: String): List<String>? = if ('#' in username || username !in clashed) null
        else users.values.filter { it.entry.username == username }.map { username + "#" + it.entry.userId.hex().take(8) }
    /** Gives an account another name without the uniqueness check (the clash a partition would produce). */
    fun renameForTest(user: ByteArray, name: String) = synchronized(lock) {
        val u = users.getValue(user.hex())
        users[user.hex()] = User(u.record, u.entry.toBuilder().setUsername(name).build())
    }
    /** Every username backup.auth was asked about, in order. */
    val backupAuthNames: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** Test hooks. */
    fun backupOf(user: ByteArray): BackupMeta? = synchronized(lock) { backups[user.hex()]?.second }
    fun loginInfo(user: ByteArray): Pair<Int, Long> = synchronized(lock) { keys[user.hex()]!!.let { it.registrationId to it.installedMs } }
}
