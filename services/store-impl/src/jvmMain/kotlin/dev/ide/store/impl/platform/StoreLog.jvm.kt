package dev.ide.store.impl.platform

import dev.ide.platform.log.Log

internal actual fun storeLog(tag: String, message: String, error: Throwable?) {
    Log.logger(tag).warn(message, error)
}
