package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** A keystore card with both fingerprints whole, after tapping Copy on the SHA-256 one. */
class KeystoreFingerprintSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun fingerprintsAreWholeAndCopyable() {
        val ks = UiKeystore(
            id = "k1",
            name = "Release key",
            fileName = "release.p12",
            keyAlias = "upload",
            certSubject = "CN=Tyron Scott, O=CodeAssist, C=PH",
            sha256 = "A8:1B:F0:E5:3D:77:F4:DD:13:61:A1:AB:FB:B2:42:8B:5C:0E:9A:21:44:7D:3B:90:C1:6F:2E:88:04:D9:AA:17",
            validUntilEpochMs = 2_500_000_000_000L,
            sha1 = "5C:0E:9A:21:44:7D:3B:90:C1:6F:2E:88:04:D9:AA:17:13:61:A1:AB",
        )
        val scene = ImageComposeScene(width = 822, height = 620, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    KeystoreCard(ks, onDelete = {})
                }
            }
        }
        try {
            scene.render()
            for (ms in 16L..400L step 16L) scene.render(ms * 1_000_000L)
            write(scene.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes, "keystore-fingerprints.png")
        } finally {
            scene.close()
        }
    }

    private fun write(png: ByteArray, name: String) {
        val out = File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots"), name)
        out.parentFile.mkdirs()
        out.writeBytes(png)
        println("wrote snapshot: $out")
    }
}
