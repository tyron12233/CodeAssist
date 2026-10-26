package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.resolve.PermissionContext
import dev.ide.lang.resolve.PermissionGuards
import dev.ide.lang.resolve.PermissionRequirement
import dev.ide.lang.resolve.PlatformPermissions
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin missing-permission check: a platform call whose permission the manifest lacks, or a dangerous one
 * nothing checks at run time. The platform is a small bytecode jar standing in for `android.jar`, and the
 * permission table a stub [PlatformPermissions].
 */
class KotlinPermissionCheckTest {

    private fun permissions(
        body: String,
        declared: Set<String> = emptySet(),
        targetSdk: Int = 34,
        application: Boolean = true,
    ): List<Diagnostic> {
        context = PermissionContext(table, declared, minSdk = 21, targetSdk = targetSdk, checkManifest = application,
            permissionLevel = { if (it == BLUETOOTH_CONNECT) 31 else null })
        val code = """
            package demo
            import android.location.LocationManager
            import android.os.Vibrator
            import android.bluetooth.BluetoothAdapter
            import android.media.AudioRecord
            $body
        """.trimIndent()
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use${counter.incrementAndGet()}.kt")))
        val all = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        return all.filter { it.code == KotlinDiagnosticCodes.MISSING_PERMISSION }
    }

    @Test
    fun aPermissionMissingFromTheManifestIsReported() {
        val d = permissions("fun m(lm: LocationManager) { lm.getLastKnownLocation(\"gps\") }")
        assertEquals(1, d.size, d.toString())
        assertEquals(Severity.WARNING, d[0].severity)
        assertEquals(
            "Missing permissions required by LocationManager.getLastKnownLocation: " +
                "android.permission.ACCESS_COARSE_LOCATION or android.permission.ACCESS_FINE_LOCATION",
            d[0].message,
        )
    }

    @Test
    fun aDeclaredDangerousPermissionNeedsARuntimeCheck() {
        val d = permissions("fun m(lm: LocationManager) { lm.getLastKnownLocation(\"gps\") }", setOf(FINE))
        assertEquals(listOf(PermissionGuards.UNCHECKED_MESSAGE + " (needs $FINE)"), d.map { it.message })
    }

    @Test
    fun aCheckInTheFunctionHandlesIt() {
        assertEquals(emptyList(), permissions("""
            fun m(lm: LocationManager, granted: Int) {
                if (ContextCompat.checkSelfPermission(this, FINE) != PackageManager.PERMISSION_GRANTED) return
                lm.getLastKnownLocation("gps")
            }""", setOf(FINE)))
        assertEquals(emptyList(), permissions("""
            fun m(lm: LocationManager) { if (hasLocationPermission()) lm.getLastKnownLocation("gps") }
            fun hasLocationPermission() = true""", setOf(FINE)))
    }

    @Test
    fun catchingSecurityExceptionHandlesIt() {
        assertEquals(emptyList(), permissions("""
            fun m(lm: LocationManager) {
                try { lm.getLastKnownLocation("gps") } catch (e: SecurityException) { }
                runCatching { lm.getLastKnownLocation("gps") }
            }""", setOf(FINE)))
        assertEquals(1, permissions("""
            fun m(lm: LocationManager) { try { lm.getLastKnownLocation("gps") } catch (e: IllegalStateException) { } }""",
            setOf(FINE)).size, "an unrelated catch does not handle it")
    }

    @Test
    fun suppressLintAndRequiresPermissionExemptTheDeclaration() {
        assertEquals(emptyList(), permissions("""
            @SuppressLint("MissingPermission")
            fun m(lm: LocationManager) { lm.getLastKnownLocation("gps") }"""))
        assertEquals(emptyList(), permissions("""
            @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            fun m(lm: LocationManager) { lm.getLastKnownLocation("gps") }""", setOf(FINE)))
    }

    @Test
    fun aNormalPermissionNeedsOnlyTheManifest() {
        assertEquals(emptyList(), permissions("fun m(v: Vibrator) { v.vibrate(10L) }", setOf(VIBRATE)))
        assertEquals(1, permissions("fun m(v: Vibrator) { v.vibrate(10L) }").size)
    }

    @Test
    fun belowTargetSdk23NothingIsCheckedAtRunTime() {
        assertEquals(emptyList(), permissions("fun m(lm: LocationManager) { lm.getLastKnownLocation(\"gps\") }", setOf(FINE), targetSdk = 22))
    }

    @Test
    fun aConstructorAndAnAccessorPropertyAreChecked() {
        val ctor = permissions("fun m() { AudioRecord(1) }")
        assertEquals(listOf("Missing permissions required by AudioRecord: android.permission.RECORD_AUDIO"), ctor.map { it.message })
        val accessor = permissions("fun m(a: BluetoothAdapter) { a.bondedDevices }")
        assertEquals(
            listOf("Missing permissions required by BluetoothAdapter.getBondedDevices: $BLUETOOTH_CONNECT"),
            accessor.map { it.message },
        )
    }

    @Test
    fun aPermissionNewerThanTheTargetSdkIsNotEnforced() {
        assertEquals(emptyList(), permissions("fun m(a: BluetoothAdapter) { a.bondedDevices }", targetSdk = 30))
    }

    @Test
    fun aLibraryIsNotHeldToItsOwnManifest() {
        val d = permissions("fun m(lm: LocationManager) { lm.getLastKnownLocation(\"gps\") }", application = false)
        assertEquals(listOf(PermissionGuards.UNCHECKED_MESSAGE + " (needs $COARSE or $FINE)"), d.map { it.message })
        assertTrue(permissions("fun m(v: Vibrator) { v.vibrate(10L) }", application = false).isEmpty())
    }

    @Test
    fun theFixChecksThePermissionAndReturns() {
        context = PermissionContext(table, setOf(FINE), minSdk = 21, targetSdk = 34)
        val code = "package demo\n\nimport android.location.LocationManager\n\nfun m(lm: LocationManager) {\n    lm.getLastKnownLocation(\"gps\")\n}\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Fix${counter.incrementAndGet()}.kt")))
        val fixed = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            val d = analyzer.analyze(doc.file).diagnostics.single { it.code == KotlinDiagnosticCodes.MISSING_PERMISSION }
            val (perms, anyOf) = PermissionGuards.permissionsNamedIn(d.message)
            val fix = assertNotNull(analyzer.permissionCheckFix(doc.file, d.range.start, perms, anyOf))
            assertEquals("Add permission check", fix.title)
            fix.edits.sortedByDescending { it.offset }
                .fold(code) { t, e -> t.substring(0, e.offset) + e.newText + t.substring(e.offset + e.oldLength) }
        }
        assertTrue(
            "    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {\n" in fixed &&
                "        return\n    }\n    lm.getLastKnownLocation" in fixed &&
                "import android.Manifest" in fixed && "import android.content.pm.PackageManager" in fixed,
            fixed,
        )
        // No fix where `return` would not compile: a function returning a value.
        val valued = "package demo\nimport android.location.LocationManager\nfun m(lm: LocationManager): Any? {\n    lm.getLastKnownLocation(\"gps\")\n    return null\n}\n"
        val doc2 = SnippetDoc(valued, DiskFile(srcDir.resolve("Fix${counter.incrementAndGet()}.kt")))
        runBlocking {
            analyzer.incrementalParser.parseFull(doc2)
            val d = analyzer.analyze(doc2.file).diagnostics.single { it.code == KotlinDiagnosticCodes.MISSING_PERMISSION }
            assertNull(analyzer.permissionCheckFix(doc2.file, d.range.start, listOf(FINE), true))
        }
    }

    companion object {
        private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        private const val VIBRATE = "android.permission.VIBRATE"
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

        private val counter = AtomicInteger()
        @Volatile private var context: PermissionContext? = null

        private val table = object : PlatformPermissions {
            private val byKey = mapOf(
                "android.location.LocationManager#getLastKnownLocation#1" to PermissionRequirement(listOf(COARSE, FINE), allRequired = false),
                "android.os.Vibrator#vibrate#1" to PermissionRequirement(listOf(VIBRATE), allRequired = true),
                "android.media.AudioRecord#<init>#1" to PermissionRequirement(listOf("android.permission.RECORD_AUDIO"), allRequired = true),
                "android.bluetooth.BluetoothAdapter#getBondedDevices#0" to PermissionRequirement(listOf(BLUETOOTH_CONNECT), allRequired = true),
            )
            override fun requirement(ownerClass: String, name: String, arity: Int) = byKey["$ownerClass#$name#$arity"]
        }

        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), platformJar())))
            .also { it.permissionContext = { context } }

        private fun platformJar(): Path {
            val jar = Files.createTempFile("platform", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun put(name: String, bytes: ByteArray) {
                    zos.putNextEntry(ZipEntry("$name.class")); zos.write(bytes); zos.closeEntry()
                }
                put("android/location/LocationManager", classBytes("android/location/LocationManager") {
                    method("getLastKnownLocation", "(Ljava/lang/String;)Ljava/lang/Object;")
                })
                put("android/os/Vibrator", classBytes("android/os/Vibrator") { method("vibrate", "(J)V") })
                put("android/media/AudioRecord", classBytes("android/media/AudioRecord") { method("<init>", "(I)V") })
                put("android/bluetooth/BluetoothAdapter", classBytes("android/bluetooth/BluetoothAdapter") {
                    method("getBondedDevices", "()Ljava/util/Set;")
                })
            }
            return jar
        }

        private fun classBytes(name: String, members: ClassWriter.() -> Unit): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            cw.members()
            cw.visitEnd()
            return cw.toByteArray()
        }

        private fun ClassWriter.method(name: String, descriptor: String) {
            visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(1, 4); visitEnd()
            }
        }
    }
}
