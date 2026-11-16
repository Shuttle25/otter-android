package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.GroupState
import com.google.protobuf.ByteString
import java.security.MessageDigest

/**
 * Deterministic acceptance rules for signed group state (docs/02 §Groups, D33, D36, D37). Pure: no I/O, no clock,
 * no notion of "me", so every client reaches the same verdict for the same inputs.
 */
object GroupRules {
    const val MAX_MEMBERS = 100
    const val MAX_NAME = 64

    /** A state this client has accepted, with the exact signed bytes (used for the D37 tie-break). */
    class Applied(val state: GroupState, val signer: ByteArray, val stateBytes: ByteArray = state.toByteArray())

    sealed interface Verdict {
        data object Accept : Verdict
        data class Reject(val reason: String) : Verdict
        /** Not valid yet against what we have; retried after every accepted update (D36). */
        data class Park(val reason: String) : Verdict
    }

    /**
     * @param current the state we hold now (null: unknown group)
     * @param previous the state [current] replaced (needed to judge a concurrent edit at the same version)
     * @param rekeys the winning verified REKEYs this client knows, old user id → new user id (D112)
     * @param currentSignerRevoked [current] was signed by an account revoked since (REVOCATION, REKEY, duplicate-invite
     *   loser). Its signature no longer authorizes anything, so a candidate that is valid against [previous] is accepted
     *   too, and wins a same-version conflict whatever the ids: phones that refused the revoked signer's state (the caller
     *   rejects states signed by a revoked account outright) and phones that took it before they learned of the
     *   revocation then converge on the next honest state.
     */
    fun evaluate(
        current: Applied?, previous: GroupState?, candidate: GroupState, signer: ByteArray,
        candidateBytes: ByteArray = candidate.toByteArray(), rekeys: Map<ByteString, ByteString> = emptyMap(),
        currentSignerRevoked: Boolean = false,
    ): Verdict {
        structuralError(candidate)?.let { return Verdict.Reject(it) }
        if (current == null) return againstBase(null, candidate, signer, rekeys)
        val cur = current.state
        if (!candidate.groupId.equals(cur.groupId)) return Verdict.Reject("other group")
        if (candidate.family != cur.family || candidate.createdBy != cur.createdBy || candidate.createdMs != cur.createdMs) {
            return Verdict.Reject("immutable field changed")
        }
        return when {
            candidate.version < cur.version -> Verdict.Reject("old")
            candidate.version > cur.version -> againstBase(cur, candidate, signer, rekeys).let { v ->
                if (v != Verdict.Accept && currentSignerRevoked && previous != null &&
                    againstBase(previous, candidate, signer, rekeys) == Verdict.Accept) Verdict.Accept else v
            }
            candidateBytes.contentEquals(current.stateBytes) -> Verdict.Reject("duplicate")
            currentSignerRevoked && previous != null && againstBase(previous, candidate, signer, rekeys) == Verdict.Accept -> Verdict.Accept
            else -> concurrent(current, previous, candidate, signer, candidateBytes, rekeys)
        }
    }

    /** Successor rules against [base] (null = no state known: creation, or trust-on-first-use by an admin). */
    private fun againstBase(base: GroupState?, candidate: GroupState, signer: ByteArray, rekeys: Map<ByteString, ByteString>): Verdict {
        if (base == null) {
            val signerIsAdmin = candidate.adminsList.any { it.toByteArray().contentEquals(signer) }
            val signerIsMember = candidate.membersList.any { it.toByteArray().contentEquals(signer) }
            return when {
                candidate.version == 1L && signer.contentEquals(candidate.createdBy.toByteArray()) && signerIsAdmin -> Verdict.Accept
                candidate.version == 1L -> Verdict.Reject("creation not signed by its creator")
                // A member added later never sees the creation: an admin of the candidate vouches for it.
                signerIsAdmin -> Verdict.Accept
                // D112: a re-keyed account (a known REKEY's new id) learns its group from the swap, which a plain member
                // signs when the old account was the only admin (so the new one is an admin of it); the account held that
                // place under its old id. Anything else from a non-admin stays parked.
                signerIsMember && candidate.adminsList.any { it in rekeys.values } -> Verdict.Accept
                else -> Verdict.Park("unknown group")
            }
        }
        val signerIsAdmin = base.adminsList.any { it.toByteArray().contentEquals(signer) }
        val signerIsMember = base.membersList.any { it.toByteArray().contentEquals(signer) }
        if (candidate.version == base.version + 1) {
            return when {
                signerIsAdmin -> Verdict.Accept
                isSelfLeave(base, candidate, signer) -> Verdict.Accept
                signerIsMember && isRekeySwap(base, candidate, rekeys) -> Verdict.Accept
                signerIsMember && looksLikeSwap(base, candidate) -> Verdict.Park("unknown rekey")
                !signerIsMember -> Verdict.Park("signer not a member yet")
                else -> Verdict.Reject("signer is not an admin")
            }
        }
        // version gap: missed steps are irrelevant for an admin of our state
        return if (signerIsAdmin) Verdict.Accept else Verdict.Park("version gap")
    }

    private fun isSelfLeave(base: GroupState, candidate: GroupState, signer: ByteArray): Boolean {
        if (!base.membersList.any { it.toByteArray().contentEquals(signer) }) return false
        if (candidate.name != base.name) return false
        fun minus(list: List<com.google.protobuf.ByteString>) = list.map { Key(it.toByteArray()) }.filterNot { it == Key(signer) }.toSet()
        return candidate.membersList.map { Key(it.toByteArray()) }.toSet() == minus(base.membersList) &&
            candidate.adminsList.map { Key(it.toByteArray()) }.toSet() == minus(base.adminsList)
    }

    /**
     * D112: [candidate] is [base] with one or more members whose REKEY we know replaced by their new ids, in members and
     * admins alike (the admin flag moves with the account); nothing else changed. A new id must not be a member already
     * (that would be a plain removal of the old one).
     */
    private fun isRekeySwap(base: GroupState, candidate: GroupState, rekeys: Map<ByteString, ByteString>): Boolean {
        if (candidate.name != base.name) return false
        val cand = candidate.membersList.toSet()
        val swapped = base.membersList.filter { it !in cand && rekeys[it] != null }.toSet()
        if (swapped.isEmpty()) return false
        if (swapped.any { rekeys.getValue(it) in base.membersList }) return false
        fun map(l: List<ByteString>) = l.map { if (it in swapped) rekeys.getValue(it) else it }.toSet()
        return cand == map(base.membersList) && candidate.adminsList.toSet() == map(base.adminsList)
    }

    /** Same name, exactly one member out and one in, the admin flag carried over: a swap whose REKEY we may not know yet. */
    private fun looksLikeSwap(base: GroupState, candidate: GroupState): Boolean {
        if (candidate.name != base.name) return false
        val out = base.membersList.filter { it !in candidate.membersList }
        val inn = candidate.membersList.filter { it !in base.membersList }
        if (out.size != 1 || inn.size != 1) return false
        val wasAdmin = out[0] in base.adminsList
        return (inn[0] in candidate.adminsList) == wasAdmin &&
            candidate.adminsList.filter { it != inn[0] }.toSet() == base.adminsList.filter { it != out[0] }.toSet()
    }

    /** D37: two different states at the same version. Both must be valid successors of [previous]. */
    private fun concurrent(
        current: Applied, previous: GroupState?, candidate: GroupState, signer: ByteArray, candidateBytes: ByteArray,
        rekeys: Map<ByteString, ByteString>,
    ): Verdict {
        if (againstBase(previous, candidate, signer, rekeys) != Verdict.Accept) return Verdict.Reject("invalid concurrent state")
        val bySigner = compareUnsigned(signer, current.signer)
        val candidateWins = if (bySigner != 0) bySigner < 0 else compareUnsigned(sha256(candidateBytes), sha256(current.stateBytes)) < 0
        return if (candidateWins) Verdict.Accept else Verdict.Reject("superseded")
    }

    /** Null if well-formed, else the reason. */
    fun structuralError(s: GroupState): String? {
        if (s.groupId.size() != 16) return "group id"
        if (s.createdBy.size() != 16) return "created_by"
        if (s.version < 1) return "version"
        val n = s.name.codePointCount(0, s.name.length)
        if (s.name != s.name.trim() || n < 1 || n > MAX_NAME) return "name"
        if (s.membersCount > MAX_MEMBERS) return "too many members"
        val members = s.membersList.map { Key(it.toByteArray()) }
        if (members.any { it.bytes.size != 16 }) return "member id"
        if (members.toSet().size != members.size) return "duplicate member"
        val admins = s.adminsList.map { Key(it.toByteArray()) }
        if (admins.toSet().size != admins.size) return "duplicate admin"
        if (!members.containsAll(admins)) return "admin not a member"
        if (members.isNotEmpty() && admins.isEmpty()) return "no admin"
        return null
    }

    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Key && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
