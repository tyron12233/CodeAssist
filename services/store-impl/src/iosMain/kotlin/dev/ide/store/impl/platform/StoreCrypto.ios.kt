package dev.ide.store.impl.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** CommonCrypto, which every Apple platform ships; no dependency and no hand-written SHA-256. */
@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(bytes: ByteArray): String {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            bytes.usePinned { input ->
                CC_SHA256(input.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
            }
        }
    }
    return digest.toByteArray().toHexString()
}
