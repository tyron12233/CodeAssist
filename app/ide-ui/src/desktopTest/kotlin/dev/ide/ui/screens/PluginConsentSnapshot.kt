package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.components.PluginConsent
import dev.ide.ui.components.describeCapability
import dev.ide.ui.components.publisherLine
import dev.ide.ui.components.shortSignature
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate shown before an installed plugin is allowed to run.
 *
 * Rendered off-screen so the wording can actually be read, because the wording is the feature: this is the
 * only place the user is told that a plugin is not sandboxed. The awkward cases are covered rather than the
 * happy path alone — an unreadable certificate, a plugin that declares nothing, and a capability this build
 * has never heard of are exactly what a single tidy screenshot would hide.
 */
class PluginConsentSnapshot {

    private fun plugin(
        capabilities: List<String> = listOf("ui.action", "ui.settingsPage"),
        signature: String? = "c".repeat(64),
    ) = UiPluginInfo(
        id = "com.example.hello",
        name = "Hello Plugin",
        version = "1.0.0",
        description = "Adds a Hello command and a settings page.",
        essential = false,
        enabled = false,
        builtIn = false,
        origin = "com.example.hello",
        needsConsent = true,
        capabilities = capabilities,
        signature = signature,
    )

    @Test
    fun renderSigned() = snapshot("plugin-consent.png", plugin())

    @Test
    fun renderUnidentifiedPublisher() = snapshot("plugin-consent-unsigned.png", plugin(signature = null))

    @Test
    fun renderDeclaresNothing() = snapshot("plugin-consent-no-capabilities.png", plugin(capabilities = emptyList()))

    @Test
    fun renderUnrecognisedCapability() =
        snapshot("plugin-consent-unknown-capability.png", plugin(capabilities = listOf("quantum.entangle")))

    /** Shortened for reading, but both ends survive so the digest can still be compared. */
    @Test
    fun `a shortened signature keeps both ends of the digest`() {
        val hex = "0123456789abcdef" + "f".repeat(32) + "fedcba9876543210"
        val short = shortSignature(hex)
        assertTrue(short.startsWith("01234567"), short)
        assertTrue(short.endsWith("76543210"), short)
        assertTrue(short.length < 24, "still unreadable at ${short.length} chars")
    }

    /** An unreadable certificate is stated, not quietly left out. */
    @Test
    fun `an unreadable certificate is called out`() {
        val line = publisherLine(plugin(signature = null))
        assertTrue("could not be identified" in line, line)
        assertTrue("Signed by" !in line, line)
    }

    /** A capability this build does not recognise reaches the screen verbatim. */
    @Test
    fun `an unrecognised capability is shown as written`() {
        assertEquals("quantum.entangle", describeCapability("quantum.entangle"))
        assertEquals("Add a page to Settings", describeCapability("ui.settingsPage"))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, plugin: UiPluginInfo) {
        val scene = ImageComposeScene(width = 820, height = 1200, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    PluginConsent(plugin, onRefuse = {}, onAccept = {})
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
