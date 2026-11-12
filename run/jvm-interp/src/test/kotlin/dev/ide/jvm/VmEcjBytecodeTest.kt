package dev.ide.jvm

import dev.ide.jvm.fixtures.Java21
import dev.ide.jvm.fixtures.PatternSwitch
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * The Java fixtures again, compiled by ecj: the compiler the IDE builds a user's program with, so its bytecode
 * is what the VM actually meets on a device. ecj lowers the same source differently from javac (its own switch
 * tables for enums, its own shape for pattern-switch labels and record patterns), which the javac-compiled
 * fixtures cannot show. Each method is interpreted from ecj's class files and compared with the real result of
 * the javac-compiled class on the test classpath.
 */
class VmEcjBytecodeTest {

    @TestFactory
    fun java21FeaturesCompiledByEcj(): List<DynamicTest> = featuresOf(Java21::class.java, "()Ljava/lang/String;") { emptyList() }

    @TestFactory
    fun patternSwitchesCompiledByEcj(): List<DynamicTest> =
        featuresOf(PatternSwitch::class.java, "(I)Ljava/lang/String;") { (0..10).toList() }

    private fun featuresOf(fixture: Class<*>, descriptor: String, inputs: () -> List<Int>): List<DynamicTest> {
        val owner = fixture.name.replace('.', '/')
        val arity = if (descriptor.startsWith("()")) 0 else 1
        return fixture.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == arity && it.returnType == String::class.java }
            .filter { arity == 0 || it.parameterTypes[0] == Int::class.javaPrimitiveType }
            .sortedBy { it.name }
            .map { m ->
                DynamicTest.dynamicTest(m.name) {
                    val vm = Vm(source = ecjClasses)
                    if (arity == 0) {
                        assertEquals(m.invoke(null), vm.invokeStatic(owner, m.name, descriptor, emptyList<Any?>()), m.name)
                    } else {
                        for (n in inputs()) {
                            val real = runCatching { m.invoke(null, n) }
                            if (real.isFailure) continue // an input outside what the method takes
                            assertEquals(real.getOrNull(), vm.invokeStatic(owner, m.name, descriptor, listOf(n)), "${m.name}($n)")
                        }
                    }
                }
            }
    }

    private companion object {
        /** The fixtures' class files as ecj writes them; anything else comes from the test classpath. */
        val ecjClasses: ClassBytesSource by lazy {
            val out = Files.createTempDirectory("ecj-fixtures")
            val sources = fixtureSources()
            val log = StringWriter()
            val ok = BatchCompiler.compile(
                arrayOf("-21", "-proc:none", "-nowarn", "-d", out.toString()) + sources.map { it.toString() },
                PrintWriter(log), PrintWriter(log), null,
            )
            check(ok) { "ecj could not compile the fixtures:\n$log" }
            val fallback = ClassBytesSource.fromClasspath()
            ClassBytesSource { name ->
                out.resolve("$name.class").takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } ?: fallback.bytesFor(name)
            }
        }

        fun fixtureSources(): List<Path> {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null) {
                for (candidate in listOf("src/test/java/dev/ide/jvm/fixtures", "run/jvm-interp/src/test/java/dev/ide/jvm/fixtures")) {
                    val fixtures = dir.resolve(candidate)
                    if (Files.isDirectory(fixtures)) return listOf("Java21.java", "PatternSwitch.java").map { fixtures.resolve(it) }
                }
                dir = dir.parent
            }
            error("the fixture sources were not found above ${Paths.get("").toAbsolutePath()}")
        }
    }
}
