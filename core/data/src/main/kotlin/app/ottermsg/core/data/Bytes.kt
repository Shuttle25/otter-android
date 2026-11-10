package app.ottermsg.core.data

import com.google.protobuf.ByteString

internal fun bs(b: ByteArray): ByteString = ByteString.copyFrom(b)
