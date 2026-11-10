package app.ottermsg.core.database.model

object MessageStatus { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val READ = 3; const val FAILED = 4 }

object MessageKind { const val TEXT = 0; const val SYSTEM_UNDECRYPTABLE = 1 }
