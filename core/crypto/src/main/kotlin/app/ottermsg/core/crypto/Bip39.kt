package app.ottermsg.core.crypto

import java.security.MessageDigest

/** BIP-39 mnemonic <-> 256-bit entropy. The entropy itself is the OtterMSG recovery seed. */
object Bip39 {
    private val words: List<String> by lazy {
        // Absolute path: R8 repackages this class, so a path relative to it would not resolve in release builds.
        checkNotNull(Bip39::class.java.getResourceAsStream("/app/ottermsg/core/crypto/bip39-english.txt")) { "bip39 word list missing" }.bufferedReader().readLines().filter { it.isNotBlank() }
            .also { require(it.size == 2048) }
    }
    private val index: Map<String, Int> by lazy { words.withIndex().associate { it.value to it.index } }

    /** True for a word of the BIP-39 English list (input checks while typing). */
    fun isWord(w: String): Boolean = w.trim().lowercase() in index

    fun toMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == 32) { "entropy must be 32 bytes" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0]
        val bits = BooleanArray(264)
        for (i in 0 until 256) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        for (i in 0 until 8) bits[256 + i] = (checksum.toInt() shr (7 - i)) and 1 == 1
        return List(24) { w -> var v = 0; for (b in 0 until 11) v = (v shl 1) or (if (bits[w * 11 + b]) 1 else 0); words[v] }
    }

    fun toEntropy(mnemonic: List<String>): ByteArray {
        val ws = mnemonic.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        require(ws.size == 24) { "expected 24 words, got ${ws.size}" }
        val bits = BooleanArray(264)
        ws.forEachIndexed { w, word ->
            val v = index[word] ?: throw IllegalArgumentException("unknown word: $word")
            for (b in 0 until 11) bits[w * 11 + b] = (v shr (10 - b)) and 1 == 1
        }
        val entropy = ByteArray(32)
        for (i in 0 until 256) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF
        var got = 0
        for (i in 0 until 8) got = (got shl 1) or (if (bits[256 + i]) 1 else 0)
        require(got == checksum) { "bad checksum" }
        return entropy
    }
}
