package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.StoreService
import org.jetbrains.skia.EncodedImageFormat

/**
 * A backend that answers every published-screenshot request with one generated image.
 *
 * Both halves have to be stubbed, because that is how the real path works: the store resolves a storage
 * path to a cached FILE, and the project service reads that file's bytes. A fake that only did the first
 * would render nothing while looking like it had been wired up.
 */
internal class ShotBackend(private val png: ByteArray = solidPng()) : StubBackend() {
    override val store: StoreService = object : StoreService {
        override suspend fun screenshotFile(storagePath: String): String = "/cache/$storagePath"
    }

    override suspend fun imageBytes(path: String): ByteArray = png
}

/** A flat blue rectangle. What it looks like does not matter; that it is a real decodable PNG does. */
internal fun solidPng(): ByteArray {
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(640, 400)
    surface.canvas.clear(0xFF2F6FED.toInt())
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
