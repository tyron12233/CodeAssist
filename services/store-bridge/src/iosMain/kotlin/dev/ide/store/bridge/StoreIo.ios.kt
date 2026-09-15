package dev.ide.store.bridge

import dev.ide.ui.concurrent.iosIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The app's one IO pool.
 *
 * Every call this dispatcher carries blocks: `NSURLSession` waited on, a payload written, a zip unpacked.
 * `Dispatchers.Default` would spend a CPU slot per waiting socket and leave the UI's own work queued
 * behind them — see [iosIoDispatcher], which is what the rest of the app offloads to as well.
 */
actual val storeIo: CoroutineDispatcher = iosIoDispatcher
