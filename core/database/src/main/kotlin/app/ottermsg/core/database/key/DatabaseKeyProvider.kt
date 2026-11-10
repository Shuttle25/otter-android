package app.ottermsg.core.database.key

import java.io.File
import java.security.SecureRandom

class DatabaseKeyProvider(private val file: File, private val wrapper: KeyWrapper) {
    @Synchronized
    fun getOrCreate(): ByteArray {
        if (file.exists()) return wrapper.unwrap(file.readBytes())
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(wrapper.wrap(key))
        check(tmp.renameTo(file)) { "cannot store db key" }
        return key
    }
}
