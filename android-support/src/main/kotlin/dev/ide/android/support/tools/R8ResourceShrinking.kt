package dev.ide.android.support.tools

import com.android.tools.r8.AndroidResourceConsumer
import com.android.tools.r8.AndroidResourceInput
import com.android.tools.r8.AndroidResourceOutput
import com.android.tools.r8.AndroidResourceProvider
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.ResourcePath
import com.android.tools.r8.origin.Origin
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Feeds the entries of a proto-format `resources.ap_` (a zip holding `resources.pb` + the proto
 * `AndroidManifest.xml` + the `res/` files) to R8's integrated resource shrinker as [AndroidResourceInput]s,
 * each tagged with the [AndroidResourceInput.Kind] R8 needs to model resource reachability against the
 * shrunken code. This is the in-process equivalent of the R8 CLI's `--android-resources <in> <out>` input.
 */
internal class ZipResourceProvider(private val protoAp: Path) : AndroidResourceProvider {
    override fun getAndroidResources(): MutableCollection<AndroidResourceInput> {
        val out = ArrayList<AndroidResourceInput>()
        ZipFile(protoAp.toFile()).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory) continue
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                out.add(Input(entry.name, bytes))
            }
        }
        return out
    }

    override fun finished(handler: DiagnosticsHandler) {}

    private class Input(val name: String, val bytes: ByteArray) : AndroidResourceInput {
        override fun getKind(): AndroidResourceInput.Kind = when {
            name == "AndroidManifest.xml" -> AndroidResourceInput.Kind.MANIFEST
            name == "resources.pb" -> AndroidResourceInput.Kind.RESOURCE_TABLE
            name.startsWith("res/") && name.endsWith(".xml") -> AndroidResourceInput.Kind.XML_FILE
            name.startsWith("res/") -> AndroidResourceInput.Kind.RES_FOLDER_FILE
            else -> AndroidResourceInput.Kind.UNKNOWN
        }
        override fun getByteStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getPath(): ResourcePath = ResourcePath { name }
        override fun getOrigin(): Origin = Origin.unknown()
    }
}

/**
 * Collects R8's shrunk resource outputs and rewrites them into the proto-format `resources.ap_` at
 * [outAp]. R8 emits one [AndroidResourceOutput] per surviving entry (with the resource table already
 * pruned); we repack them into a zip the build then converts back to binary for packaging.
 */
internal class ZipResourceConsumer(private val outAp: Path) : AndroidResourceConsumer {
    private val entries = LinkedHashMap<String, ByteArray>()

    override fun accept(output: AndroidResourceOutput, handler: DiagnosticsHandler) {
        entries[output.path.location()] = output.byteDataView.copyByteData()
    }

    override fun finished(handler: DiagnosticsHandler) {
        // Guard the ART empty-zip footgun: a ZipOutputStream with zero entries throws "No entries". A real
        // resource archive always has a manifest + table, so an empty set means R8 produced nothing usable;
        // leave outAp absent so the caller falls back to the un-shrunk archive.
        if (entries.isEmpty()) return
        outAp.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(outAp)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
        }
    }
}
