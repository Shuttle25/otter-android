package app.ottermsg.core.database.model

object MessageStatus { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val READ = 3; const val FAILED = 4 }

object MessageKind { const val TEXT = 0; const val SYSTEM_UNDECRYPTABLE = 1; const val SYSTEM_GROUP = 2; const val PHOTO = 3 }

/** attachments.state (D48). */
object AttachmentState {
    const val UPLOADING = 0     // outgoing, chunks not all on the node
    const val UPLOADED = 1      // outgoing, done
    const val UPLOAD_FAILED = 2 // outgoing, quota/invalid; message FAILED
    const val REMOTE = 3        // incoming, not downloaded (manual, or waiting for Wi-Fi)
    const val QUEUED = 4        // incoming, download wanted
    const val DOWNLOADED = 5
    const val FAILED = 6        // incoming, gave up after 3 digest/IO failures; tap retries
    const val GONE = 7          // incoming, the node no longer has it: «Фото недоступно»
}
