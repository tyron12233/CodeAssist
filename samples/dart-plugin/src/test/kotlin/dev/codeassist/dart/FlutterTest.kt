package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class FlutterTest {

    @TempDir
    lateinit var dir: Path

    private fun write(rel: String, text: String = "x"): Path =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.toByteArray()) }

    @Test
    fun theAssetManifestIsWhatFlutterWrites() {
        // `flutter build bundle` of the stock app (cupertino_icons and nothing else): its AssetManifest.bin.
        val key = "packages/cupertino_icons/assets/CupertinoIcons.ttf"
        val expected = byteArrayOf(0x0d, 0x01, 0x07, key.length.toByte()) + key.toByteArray() +
            byteArrayOf(0x0c, 0x01, 0x0d, 0x01, 0x07, 0x05) + "asset".toByteArray() +
            byteArrayOf(0x07, key.length.toByte()) + key.toByteArray()
        assertArrayEquals(expected, FlutterAssets.assetManifest(listOf(key)))
        // Sizes from 254 on take a marker and two little-endian bytes.
        val long = "a".repeat(300)
        assertArrayEquals(byteArrayOf(0x07, 254.toByte(), 0x2c, 0x01), StandardMessageCodec.encode(long).copyOf(4))
    }

    @Test
    fun aPubspecFlutterSectionReads() {
        val yaml = """
            name: app # the app
            dependencies:
              flutter:
                sdk: flutter
            flutter:
              uses-material-design: true
              assets:
                - assets/images/
                - path: assets/data.json
              fonts:
                - family: Roboto
                  fonts:
                    - asset: fonts/Roboto-Regular.ttf
                    - asset: fonts/Roboto-Bold.ttf
                      weight: 700
        """.trimIndent()
        val flutter = (MiniYaml.parse(yaml) as Map<*, *>)["flutter"] as Map<*, *>
        assertEquals("true", flutter["uses-material-design"])
        assertEquals(listOf("assets/images/", mapOf("path" to "assets/data.json")), flutter["assets"])
        val family = (flutter["fonts"] as List<*>).single() as Map<*, *>
        assertEquals("Roboto", family["family"])
        assertEquals(listOf(mapOf("asset" to "fonts/Roboto-Regular.ttf"), mapOf("asset" to "fonts/Roboto-Bold.ttf", "weight" to "700")), family["fonts"])
        assertTrue(FlutterSdk.isFlutter(yaml))
        assertFalse(FlutterSdk.isFlutter("name: cli\ndev_dependencies:\n  flutter_test:\n    sdk: flutter\n"))
    }

    @Test
    fun theBundleHoldsTheAppsAssetsItsPackagesFontsAndTheBase() {
        write("pubspec.yaml", "name: app\nflutter:\n  uses-material-design: true\n  assets:\n    - images/\n")
        write("images/logo.png")
        write("images/sub/ignored.png")
        val icons = dir.resolve("cache/cupertino_icons")
        write("cache/cupertino_icons/pubspec.yaml", "name: cupertino_icons\nflutter:\n  fonts:\n    - family: CupertinoIcons\n      fonts:\n        - asset: assets/CupertinoIcons.ttf\n")
        write("cache/cupertino_icons/assets/CupertinoIcons.ttf")
        write(".dart_tool/package_config.json", """{"configVersion":2,"packages":[
            {"name":"cupertino_icons","rootUri":"${icons.toUri()}","packageUri":"lib/"},
            {"name":"app","rootUri":"../","packageUri":"lib/"}]}""")
        val base = dir.resolve("base")
        write("base/fonts/MaterialIcons-Regular.otf")
        write("base/shaders/ink_sparkle.frag")
        write("base/NOTICES.Z")

        val bundle = FlutterAssets.build(dir, base)
        assertEquals(
            setOf("images/logo.png", "packages/cupertino_icons/assets/CupertinoIcons.ttf", "fonts/MaterialIcons-Regular.otf", "shaders/ink_sparkle.frag", "NOTICES.Z"),
            bundle.files.keys,
        )
        assertEquals(
            """[{"family":"MaterialIcons","fonts":[{"asset":"fonts/MaterialIcons-Regular.otf"}]},{"family":"packages/cupertino_icons/CupertinoIcons","fonts":[{"asset":"packages/cupertino_icons/assets/CupertinoIcons.ttf"}]}]""",
            String(bundle.generated.getValue("FontManifest.json")),
        )
        // The app's assets come before its packages', and the Material font is not an asset-manifest entry.
        assertArrayEquals(
            FlutterAssets.assetManifest(listOf("images/logo.png", "packages/cupertino_icons/assets/CupertinoIcons.ttf")),
            bundle.generated.getValue("AssetManifest.bin"),
        )
    }

    @Test
    fun webSocketFramesRoundTrip() {
        val payload = "{\"jsonrpc\":\"2.0\"}".repeat(10).toByteArray()
        val frame = WsFrames.encode(VmService.OP_TEXT, payload, byteArrayOf(1, 2, 3, 4))
        val read = WsFrames.read(ByteArrayInputStream(frame))
        assertTrue(read.fin)
        assertEquals(VmService.OP_TEXT, read.opcode)
        assertArrayEquals(payload, read.payload)
        // A server frame: unmasked, with a 16-bit length.
        val big = ByteArray(300) { it.toByte() }
        val serverFrame = byteArrayOf(0x82.toByte(), 126, 0x01, 0x2c) + big
        assertArrayEquals(big, WsFrames.read(ByteArrayInputStream(serverFrame)).payload)
    }
}
