package dev.ide.ksp.thinspike

import dev.ide.ksp.spike.ListClassesProcessorProvider
import dev.ide.testkit.withTempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * FEASIBILITY SPIKE (task 7, the <100 MB path): can KSP's own impl run on OUR bundled Analysis API instead of
 * the 78 MB one it ships?
 *
 * KSP's runner is an uber jar bundling its own Analysis API. We extract ONLY KSP's own classes
 * (`com.google.devtools.ksp.**`) from the non-embeddable `symbol-processing-aa` (whose AA refs are the plain
 * `org.jetbrains.kotlin.analysis.*`, un-relocated) and drop its bundled AA, then run them on an isolated
 * classloader whose ONLY `org.jetbrains.kotlin.analysis.*` provider is our `:kotlin-compiler-deps` merged jar.
 * If KSP 2.3.10's impl (compiled against Kotlin 2.3.20's AA) links + runs against our AA (2.4.20-dev-6138),
 * we can ship a ~few-MB thin KSP on the compiler we ALREADY bundle — no second platform, small base APK.
 *
 * A green run proves the direction. A failure surfaces the exact AA drift (a `NoSuchMethodError` /
 * `AbstractMethodError` / missing class), which is the real spike signal — report it, then decide.
 */
class ThinKspOnOurAaSpikeTest {

    private fun classpath(prop: String): List<File> =
        (System.getProperty(prop) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }

    /** Extract entries under [prefix] from [jar] into a fresh dir; returns the dir. */
    private fun extract(jar: File, prefix: String, into: File): File {
        ZipFile(jar).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory || !e.name.startsWith(prefix)) continue
                val dst = File(into, e.name)
                dst.parentFile.mkdirs()
                zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
            }
        }
        return into
    }

    @Test
    fun kspImplRunsOnOurBundledAnalysisApi() {
        val runtime = classpath("ksp.thin.runtime.classpath")
        assumeTrue(runtime.isNotEmpty(), "ksp.thin.runtime.classpath not injected — skipping")

        // The non-embeddable runner jar (symbol-processing-aa-<ver>.jar), not the -api / -common-deps / -embeddable.
        val aaJar = runtime.firstOrNull {
            it.name.matches(Regex("symbol-processing-aa-[0-9].*\\.jar")) && "embeddable" !in it.name
        }
        assumeTrue(aaJar != null, "symbol-processing-aa (non-embeddable) not on the classpath — skipping")

        withTempDir("thin-ksp-spike") { dir ->
            val work = dir.toFile()
            // 1. Extract ONLY KSP's own classes; leave its bundled AA behind (our merged jar supplies the AA).
            val kspThinDir = extract(aaJar!!, "com/google/devtools/ksp/", File(work, "ksp-classes"))
            val classCount = kspThinDir.walkTopDown().count { it.isFile && it.extension == "class" }
            assertTrue(classCount > 0, "extracted no KSP classes from ${aaJar.name}")

            // 2. Where the spike's helper classes (ThinKspRunner, ListClassesProcessorProvider) live.
            val testClassesDir = File(ListClassesProcessorProvider::class.java.protectionDomain.codeSource.location.toURI())

            // 3. Isolated classloader (parent = JDK platform only): extracted KSP + everything on the runtime
            //    classpath EXCEPT the aa jar (whose bundled AA would shadow ours) + the spike helper classes.
            val urls = (listOf(kspThinDir) + runtime.filter { it != aaJar } + listOf(testClassesDir))
                .map { it.toURI().toURL() }.toTypedArray()
            val cl = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

            // 4. A trivial Kotlin module to process.
            val srcDir = File(work, "src").apply { mkdirs() }
            File(srcDir, "Model.kt").writeText("package demo\n\nclass Foo\ndata class Bar(val x: Int)\n")
            val outBase = File(work, "out")

            // 5. Drive KSP through the isolated loader — every com.google.devtools.ksp.impl.* call now hits the
            //    thin classes running on OUR AA. Only this reflective call crosses the boundary (JDK-typed args).
            val runner = cl.loadClass("dev.ide.ksp.thinspike.ThinKspRunner")
            val runMethod = runner.getMethod("run", File::class.java, File::class.java)
            val result = try {
                runMethod.invoke(null, srcDir, outBase) as String
            } catch (e: InvocationTargetException) {
                fail("KSP impl failed to link/run against our Analysis API:\n${e.cause?.stackTraceToString() ?: e.stackTraceToString()}")
            }

            val exitName = result.lineSequence().first()
            assertEquals("OK", exitName, "KSP-on-our-AA did not finish OK:\n$result")
            assertTrue(
                File(outBase, "kotlin/com/gen/GeneratedClasses.kt").exists(),
                "KSP ran on our AA but generated nothing.\n$result\nout tree:\n" +
                    outBase.walkTopDown().filter { it.isFile }.joinToString("\n") { it.relativeTo(outBase).path },
            )
        }
    }

    /**
     * The decisive real-ecosystem check: run the ACTUAL `room-compiler` KSP processor on OUR bundled Analysis
     * API. Room exercises far more of the AA (type resolution, annotation arguments, supertypes, generics)
     * than the trivial processor, so a green run here is strong evidence the thin-KSP-on-our-AA direction is
     * production-viable, not just a toy. Room's processor jars are added to the isolated loader; room-runtime
     * goes on KSP's library classpath.
     */
    @Test
    fun roomProcessorRunsOnOurBundledAnalysisApi() {
        val runtime = classpath("ksp.thin.runtime.classpath")
        val roomProcessor = classpath("room.processor.classpath")
        val roomLibs = classpath("room.libs.classpath")
        assumeTrue(runtime.isNotEmpty(), "ksp.thin.runtime.classpath not injected — skipping")
        assumeTrue(roomProcessor.isNotEmpty(), "room.processor.classpath not injected — skipping")
        assumeTrue(roomLibs.isNotEmpty(), "room.libs.classpath not injected — skipping")
        val aaJar = runtime.firstOrNull {
            it.name.matches(Regex("symbol-processing-aa-[0-9].*\\.jar")) && "embeddable" !in it.name
        }
        assumeTrue(aaJar != null, "symbol-processing-aa (non-embeddable) not on the classpath — skipping")

        withTempDir("thin-ksp-room-spike") { dir ->
            val work = dir.toFile()
            val kspThinDir = extract(aaJar!!, "com/google/devtools/ksp/", File(work, "ksp-classes"))
            val testClassesDir = File(ListClassesProcessorProvider::class.java.protectionDomain.codeSource.location.toURI())

            // Isolated loader = thin KSP + OUR compiler/AA + Room's processor closure + spike helpers.
            val urls = (listOf(kspThinDir) + runtime.filter { it != aaJar } + roomProcessor + listOf(testClassesDir))
                .map { it.toURI().toURL() }.distinct().toTypedArray()
            val cl = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

            val srcDir = File(work, "src").apply { mkdirs() }
            File(srcDir, "Db.kt").writeText(
                """
                package demo
                import androidx.room.*

                @Entity data class User(@PrimaryKey val id: Int, val name: String)

                @Dao interface UserDao {
                    @Query("SELECT * FROM User") suspend fun all(): List<User>
                    @Insert suspend fun insert(user: User)
                }

                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() { abstract fun userDao(): UserDao }
                """.trimIndent(),
            )
            val outBase = File(work, "out")

            val runner = cl.loadClass("dev.ide.ksp.thinspike.ThinKspRunner")
            val run = runner.getMethod("runServiceLoaded", File::class.java, File::class.java, List::class.java, Map::class.java)
            val result = try {
                run.invoke(null, srcDir, outBase, roomLibs, mapOf("room.generateKotlin" to "true")) as String
            } catch (e: InvocationTargetException) {
                fail("Room processor failed to run against our Analysis API:\n${e.cause?.stackTraceToString() ?: e.stackTraceToString()}")
            }

            val exitName = result.lineSequence().first()
            val emitted = outBase.walkTopDown().filter { it.isFile }.toList()
            assertEquals(
                "OK", exitName,
                "Room on our AA did not finish OK:\n$result\nemitted:\n" +
                    emitted.joinToString("\n") { it.relativeTo(outBase).path },
            )
            assertTrue(
                emitted.any { it.name == "AppDatabase_Impl.kt" },
                "Room ran on our AA but did not emit AppDatabase_Impl.kt.\n$result\nemitted:\n" +
                    emitted.joinToString("\n") { it.relativeTo(outBase).path },
            )
        }
    }
}
