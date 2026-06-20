package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor's completion/diagnostics path (`diagnose`/completion → the low-level ecj `Compiler` over the
 * custom [dev.ide.lang.jdt.env.JdtNameEnvironment], a classic non-module-aware `INameEnvironment`) resolves
 * **Java 17** source against `android.jar` with NO JRT image: a classic name environment keeps ecj in
 * non-modular mode at any compliance, so `java.lang.Object`/`var`/records/etc. bind straight from
 * `android.jar`'s bytes. The one platform gap is `java.lang.invoke.StringConcatFactory` — `android.jar` omits
 * it, and Java 9+ compiles string concatenation to an `invokedynamic` against it — so the fix is simply to put
 * the build-tools `core-lambda-stubs.jar` on the boot classpath (D8 desugars the indy at build time anyway).
 *
 * This test pins both halves: without the stubs a Java-17 string-concat buffer reports the spurious
 * StringConcatFactory error; with them it's clean. Skips when no Android SDK / build-tools are installed.
 */
class AndroidJava17EditorTest {

    private val code = """
        package app;
        import android.app.Activity;
        import android.os.Bundle;
        import java.util.ArrayList;
        public class T extends Activity {
            record Pt(int x, int y) {}
            protected void onCreate(Bundle b) {
                super.onCreate(b);
                var xs = new ArrayList<String>();
                xs.add("hi");
                var p = new Pt(1, 2);
                String s = "p=" + p + " n=" + xs.size();
                System.out.println(s);
            }
        }
    """.trimIndent()

    @Test
    fun java17EditorResolvesAgainstAndroidJarWithDesugarStubs() {
        val androidJar = androidJar() ?: run { println("[AndroidJava17Editor] no Android SDK — skipping"); return }
        val stubs = coreLambdaStubs() ?: run { println("[AndroidJava17Editor] no core-lambda-stubs.jar — skipping"); return }
        val dir = Files.createTempDirectory("android-j17")
        try {
            val file = StubFile(dir.resolve("app/T.java").toString(), code)

            // Without the desugar stubs: the string concatenation can't bind StringConcatFactory at Java 17.
            val withoutStubs = JdtSourceAnalyzer(ctx(dir, listOf(androidJar))).diagnose(file, code).map { it.message }
            println("[AndroidJava17Editor] without stubs: $withoutStubs")
            assertTrue(withoutStubs.any { "StringConcatFactory" in it },
                "Java 17 string concat against android.jar alone should surface the StringConcatFactory gap: $withoutStubs")

            // With core-lambda-stubs on the boot classpath: the whole Java-17 buffer (var, record, string
            // concat, android types) resolves clean — no JRT image needed for the editor path.
            val withStubs = JdtSourceAnalyzer(ctx(dir, listOf(androidJar, stubs))).diagnose(file, code).map { it.message }
            println("[AndroidJava17Editor] with stubs: $withStubs")
            assertFalse(withStubs.any { "StringConcatFactory" in it }, "stubs must resolve StringConcatFactory: $withStubs")
            assertTrue(withStubs.isEmpty(), "well-formed Java 17 buffer should produce no editor diagnostics: $withStubs")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun ctx(dir: Path, boot: List<Path>) = object : CompilationContext {
        override val sourceRoots: List<VirtualFile> = listOf(StubFile(dir.toString()))
        override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
            override val entries: List<ClasspathEntry> = emptyList()
            override fun fingerprint() = ContentHash("")
        }
        override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
            override val entries = boot.map { ClasspathEntry(StubFile(it.toString()), ClasspathEntryKind.SDK_BOOTCLASSPATH) }
            override fun fingerprint() = ContentHash(boot.joinToString())
        }
        override val languageLevel = LanguageLevel.JAVA_17
        override val outputDir: VirtualFile = StubFile("/out")
        override val processors: List<AnnotationProcessor> = emptyList()
    }

    private fun sdkRoots() = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }

    private fun coreLambdaStubs(): Path? = sdkRoots().map { it.resolve("build-tools") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("core-lambda-stubs.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }
}
