package dev.ide.jvm

import dev.ide.jvm.fixtures.Structured
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ScopedValue` and `StructuredTaskScope` for a runtime that has neither (Android, at every API level), and the
 * Java 21 thread API with them.
 *
 * Three layers, each checked: the implementations themselves ([Structured], run natively and interpreted), the
 * VM defining them under their `java.*` names from the class files it ships, and the compile side, where a
 * program written against the real names is compiled by ecj against an `android.jar` with the additions and
 * then run.
 */
class VmStructuredConcurrencyTest {

    private val features = Structured::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 }.sortedBy { it.name }

    @TestFactory
    fun theImplementationsBehaveTheSameInterpreted(): List<DynamicTest> = features.map { m ->
        DynamicTest.dynamicTest(m.name) {
            assertEquals(m.invoke(null), Vm().invokeStatic(FIXTURE, m.name, "()Ljava/lang/String;", emptyList<Any?>()), m.name)
        }
    }

    /** The fixture with its references moved to `java.lang.ScopedValue` / `java.util.concurrent.StructuredTaskScope`,
     *  as a program compiled against the platform names has them, on a VM that defines those itself. */
    @TestFactory
    fun theVmDefinesThemUnderTheirPlatformNames(): List<DynamicTest> = features.map { m ->
        DynamicTest.dynamicTest(m.name) {
            val classpath = ClassBytesSource.fromClasspath()
            val vm = Vm(source = { name ->
                classpath.bytesFor(name)?.let { if (name.startsWith(FIXTURE)) PlatformStandIns.renamedBytes(it) else it }
            })
            vm.installPlatformStandIns(force = true)
            assertEquals(m.invoke(null), vm.invokeStatic(FIXTURE, m.name, "()Ljava/lang/String;", emptyList<Any?>()), m.name)
        }
    }

    @Test
    fun everyCompiledStandInIsShippedAndRenamed() {
        val names = PlatformStandIns.compiledNames()
        assertTrue(names.any { it.endsWith("java/lang/ScopedValue") } && names.any { it.endsWith("StructuredTaskScope\$ShutdownOnFailure") }, "$names")
        for (name in names) {
            val bytes = PlatformStandIns.compiledBytes(name) ?: error("no class file shipped for $name")
            val renamed = org.objectweb.asm.ClassReader(PlatformStandIns.renamedBytes(bytes)).className
            assertTrue(renamed.startsWith("java/"), "$name was left as $renamed")
        }
    }

    /**
     * The device's whole path: a program using the Java 21 API by its real names compiles against android.jar's
     * `java.*` once [CoreJavaApiAdditions] has been applied (it does not without), and the class files ecj wrote
     * run on a VM in the state of a runtime that has none of that API.
     *
     * The program is in Java 8 syntax: that is the newest level ecj's command line accepts a boot classpath at.
     * The IDE's own compile hands the platform to ecj through its name environment, at any level.
     */
    @Test
    fun aProgramCompiledAgainstTheAdditionsRuns() {
        val androidJar = androidJar()
        assumeTrue(androidJar != null, "no android.jar on this machine")
        val work = Files.createTempDirectory("api-additions")
        val source = work.resolve("demo/Main.java").also { Files.createDirectories(it.parent); Files.write(it, PROGRAM.toByteArray()) }

        val plain = platformJar(androidJar!!, work.resolve("plain.jar"), additions = false)
        assertTrue(!compile(source, plain, work.resolve("out-plain")).first, "android.jar alone must not offer this API")

        val extended = platformJar(androidJar, work.resolve("extended.jar"), additions = true)
        val (ok, log) = compile(source, extended, work.resolve("out"))
        assertTrue(ok, "the program must compile against the additions:\n$log")

        val out = work.resolve("out")
        val classpath = ClassBytesSource.fromClasspath()
        val vm = Vm(
            source = { name -> out.resolve("$name.class").takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } ?: classpath.bytesFor(name) },
            policy = { name -> name.startsWith("demo/") },
        )
        vm.installPlatformStandIns(force = true)
        HostBackports.preferBackports = true
        try {
            assertEquals(
                "ada 6 true worker-0 fast 3",
                vm.invokeStatic("demo/Main", "run", "()Ljava/lang/String;", emptyList<Any?>()),
            )
        } finally {
            HostBackports.preferBackports = false
        }
    }

    private fun compile(source: Path, platform: Path, out: Path): Pair<Boolean, String> {
        val log = StringWriter()
        val boot = listOfNotNull(platform, lambdaStubs()).joinToString(java.io.File.pathSeparator)
        val ok = BatchCompiler.compile(
            arrayOf("-1.8", "-proc:none", "-nowarn", "-bootclasspath", boot, "-d", out.toString(), source.toString()),
            PrintWriter(log), PrintWriter(log), null,
        )
        return ok to log.toString()
    }

    /** android.jar's `java.*`/`javax.*`, as the host's core-Java platform keeps it, with or without the additions. */
    private fun platformJar(androidJar: Path, out: Path, additions: Boolean): Path {
        ZipFile(androidJar.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(out)).use { zos ->
                for (e in zip.entries()) {
                    if (e.isDirectory || !(e.name.startsWith("java/") || e.name.startsWith("javax/"))) continue
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(if (additions) CoreJavaApiAdditions.rewrite(e.name, bytes) else bytes)
                    zos.closeEntry()
                }
                if (additions) CoreJavaApiAdditions.additions { zip.getEntry(it) != null }.forEach { (name, bytes) ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return out
    }

    private companion object {
        const val FIXTURE = "dev/ide/jvm/fixtures/Structured"

        const val PROGRAM = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.Executors;
            import java.util.concurrent.Future;
            import java.util.concurrent.StructuredTaskScope;

            public class Main {
                static final ScopedValue<String> USER = ScopedValue.newInstance();

                public static String run() throws Exception {
                    String who = ScopedValue.callWhere(USER, "ada", () -> USER.get());

                    int sum = 0;
                    List<Future<Integer>> results = new ArrayList<>();
                    try (java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int i = 1; i <= 3; i++) { int n = i; results.add(executor.submit(() -> n)); }
                    }
                    for (Future<Integer> f : results) sum += f.get();

                    String[] name = new String[1];
                    Thread t = Thread.ofVirtual().name("worker-", 0).start(() -> name[0] = Thread.currentThread().getName());
                    t.join();

                    String first;
                    try (StructuredTaskScope.ShutdownOnSuccess<String> scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
                        scope.fork(() -> { Thread.sleep(30000); return "slow"; });
                        scope.fork(() -> "fast");
                        first = scope.join().result();
                    }

                    int total;
                    try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
                        StructuredTaskScope.Subtask<Integer> a = scope.fork(() -> 1);
                        StructuredTaskScope.Subtask<Integer> b = scope.fork(() -> 2);
                        scope.join().throwIfFailed();
                        total = a.get() + b.get();
                    }
                    return who + " " + sum + " " + t.isVirtual() + " " + name[0] + " " + first + " " + total;
                }
            }
        """

        fun sdk(): Path? = listOfNotNull(
            System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }

        fun newest(dir: Path?, file: String): Path? = dir?.takeIf { Files.isDirectory(it) }
            ?.let { d -> Files.list(d).use { s -> s.map { it.resolve(file) }.filter { Files.isRegularFile(it) }.toList() } }
            ?.maxByOrNull { it.parent.fileName.toString() }

        fun androidJar(): Path? = newest(sdk()?.resolve("platforms"), "android.jar")

        /** `LambdaMetafactory`/`StringConcatFactory`, which android.jar leaves out and a compiler has to see. */
        fun lambdaStubs(): Path? = newest(sdk()?.resolve("build-tools"), "core-lambda-stubs.jar")
    }
}
