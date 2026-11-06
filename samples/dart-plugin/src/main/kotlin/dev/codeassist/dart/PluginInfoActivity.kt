package dev.codeassist.dart

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * What the user sees if they launch this app directly: what the plugin is, and the licenses of everything it
 * carries. A plugin app exists to be discovered by CodeAssist; saying so is better than a blank screen, which
 * reads as a broken install.
 *
 * The licenses are read from the assets the plugin ships rather than copied into the app: the Dart SDK's and
 * Flutter's own `LICENSE`, and Flutter's `NOTICES` (every library inside the engine and the framework, Skia,
 * ICU, HarfBuzz, BoringSSL, ...), so they are always the ones for the exact bytes on the device.
 */
class PluginInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entries = ArrayList<String>()
        entries += INTRO
        entries += section("The Dart SDK ${DartSdk.VERSION}", "Built for Android by the Termux project (termux-packages, Apache License 2.0).") +
            (zipEntry(DART_ASSET, "LICENSE") ?: MISSING)
        entries += section("Flutter ${FlutterSdk.VERSION}", null) + (zipEntry(FLUTTER_ROOT_ASSET, "LICENSE") ?: MISSING)
        entries += section("kotlinx.serialization", null) + APACHE_2
        // Flutter's notices: one entry per library or group of libraries, separated by a line of dashes.
        val notices = zipEntry(FLUTTER_BASE_ASSET, "NOTICES.Z", gzipped = true)
        if (notices != null) {
            entries += section("Inside the Flutter engine and framework", null).trimEnd()
            entries += notices.split(NOTICE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        }
        setContentView(ListView(this).apply {
            divider = null
            adapter = Entries(entries)
        })
    }

    /** One text block per row, so the ~1 MB of notices is laid out a screen at a time, never all at once. */
    private inner class Entries(private val items: List<String>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            ((convertView as? TextView) ?: TextView(this@PluginInfoActivity).apply {
                setPadding(48, 24, 48, 24)
                setTextIsSelectable(true)
            }).apply {
                text = items[position]
                textSize = if (position == 0) 16f else 12f
            }
    }

    /** The text of [name] inside the stored archive [asset], or null when this build does not carry it. */
    private fun zipEntry(asset: String, name: String, gzipped: Boolean = false): String? = runCatching {
        ZipInputStream(assets.open(asset).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != name) entry = zip.nextEntry
            if (entry == null) return@use null
            val stream: InputStream = if (gzipped) GZIPInputStream(zip) else zip
            String(stream.readBytes())
        }
    }.getOrNull()

    private fun section(title: String, note: String?) = buildString {
        appendLine(title)
        appendLine("=".repeat(title.length))
        if (note != null) appendLine(note)
        appendLine()
    }

    private companion object {
        const val DART_ASSET = "dart-sdk.zip"
        const val FLUTTER_ROOT_ASSET = "flutter-root.zip"
        const val FLUTTER_BASE_ASSET = "flutter-base-assets.zip"
        const val MISSING = "(not in this build)"
        val NOTICE_SEPARATOR = Regex("(?m)^-{20,}$")

        val INTRO = """
            CodeAssist Dart

            A plugin for CodeAssist, not an app of its own.

            It adds Dart and Flutter: errors, completion and quick fixes from Dart's analysis server, Run for
            command-line programs and Flutter apps (with hot reload), live @Preview widgets in the editor, tests,
            and pub, with a Dart SDK and Flutter for this device's ABI inside.

            Open CodeAssist, then Settings and tools > Plugins to allow it to run.

            Below: the licenses of the software this plugin carries.
        """.trimIndent()

        val APACHE_2 = """
            kotlinx.serialization, by JetBrains and its contributors (github.com/Kotlin/kotlinx.serialization).

            Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
            compliance with the License. You may obtain a copy of the License at
            https://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software distributed under the License is
            distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
            implied. See the License for the specific language governing permissions and limitations under the
            License.
        """.trimIndent()
    }
}
