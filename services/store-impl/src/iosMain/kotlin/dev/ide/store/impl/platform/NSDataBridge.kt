package dev.ide.store.impl.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** `ByteArray` ↔ `NSData`, the one conversion every Foundation call in this module needs. */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
