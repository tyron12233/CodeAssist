package dev.ide.store.impl.platform

import java.security.MessageDigest

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
