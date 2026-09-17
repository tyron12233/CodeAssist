package dev.ide.kotlin.classfile

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual class Lock {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
