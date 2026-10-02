package dev.ide.vm.fixtures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Real Compose UI rendered to pixels through `ImageComposeScene`: composition, layout, text shaping and
 * skia drawing. Under the VM all of it but skia itself is interpreted from the desktop jars.
 */
object Render {

    @Composable
    private fun Sample() {
        MaterialTheme {
            Surface {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hello from CodeAssist")
                    Button(onClick = {}) { Text("Press me") }
                }
            }
        }
    }

    private fun renderImage(): Image =
        ImageComposeScene(width = 360, height = 220, density = Density(2f)) { Sample() }.use { it.render() }

    /** The rendered frame summarized: size, how many pixels are not the background, and a hash of them all. */
    @JvmStatic
    fun sampleSummary(): String {
        val image = renderImage()
        val bitmap = Bitmap.makeFromImage(image)
        val pixels = bitmap.readPixels() ?: return "no pixels"
        val background0 = pixels[0]
        var inked = 0
        var hash = -0x7ee3623b // FNV-1a offset basis
        for (k in pixels.indices step 4) {
            if (pixels[k] != background0 || pixels[k + 1] != pixels[1] || pixels[k + 2] != pixels[2]) inked++
        }
        for (b in pixels) hash = (hash xor (b.toInt() and 0xFF)) * 16777619
        return "${image.width}x${image.height} inked=$inked hash=${hash.toUInt().toString(16)}"
    }

    /** The rendered frame as a PNG, to look at. */
    @JvmStatic
    fun samplePng(): ByteArray = renderImage().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
