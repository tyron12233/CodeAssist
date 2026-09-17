@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

internal actual fun sha256Hex(bytes: ByteArray): String = memScoped {
    val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    // CoreCrypto reads through a raw pointer, so the input has to be pinned for the call. An empty array
    // has no element whose address can be taken, which is why the null pointer is passed explicitly rather
    // than reached through `addressOf(0)`.
    if (bytes.isEmpty()) {
        CC_SHA256(null, 0.convert(), digest)
    } else {
        bytes.usePinned { pinned -> CC_SHA256(pinned.addressOf(0), bytes.size.convert(), digest) }
    }
    buildString(CC_SHA256_DIGEST_LENGTH * 2) {
        for (i in 0 until CC_SHA256_DIGEST_LENGTH) {
            val byte = digest[i].toInt()
            append(HEX[byte shr 4])
            append(HEX[byte and 0xF])
        }
    }
}

private const val HEX = "0123456789abcdef"
