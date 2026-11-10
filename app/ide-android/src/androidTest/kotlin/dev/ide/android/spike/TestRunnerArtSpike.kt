package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramIo
import dev.ide.build.jvm.run.VmProgramInterpreter
import dev.ide.build.jvm.test.TestDiscovery
import dev.ide.build.jvm.test.TestProtocol
import dev.ide.build.jvm.test.TestRunnerGenerator
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The IDE's unit-test runner and the VM's reflection over interpreted classes, on ART: what a phone test run
 * does, with every generated class (peers, reflection mirrors, annotation implementations, the rule Statement)
 * dexed by [DexPeerFactory] at minSdk 26, and JUnit + hamcrest interpreted from their jars. The desktop twins are
 * :jvm-build's TestRunnerTest and :jvm-interp's VmInterpreterTest; the fixtures are theirs, staged by
 * `bundleTestRunSpikeAsset`.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.TestRunnerArtSpike
 */
@RunWith(AndroidJUnit4::class)
class TestRunnerArtSpike {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The staged fixtures, copied out of the test APK's assets to a directory the runner can read. */
    private fun stage(): Path {
        val root = instrumentation.targetContext.cacheDir.toPath().resolve("testrun-spike")
        root.toFile().deleteRecursively()
        copyAsset("testrun", root)
        return root
    }

    private fun copyAsset(path: String, to: Path) {
        val assets = instrumentation.context.assets
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            Files.createDirectories(to.parent)
            assets.open(path).use { Files.copy(it, to) }
        } else {
            children.forEach { copyAsset("$path/$it", to.resolve(it)) }
        }
    }

    private class Result(val event: String, val cls: String, val method: String, val message: String)

    private fun parse(text: String): List<Result> {
        val out = ArrayList<Result>()
        var i = 0
        while (true) {
            val at = text.indexOf(TestProtocol.MARK, i).takeIf { it >= 0 } ?: break
            var p = at + TestProtocol.MARK.length
            fun word(): String { val e = text.indexOf(' ', p); return text.substring(p, e).also { p = e + 1 } }
            fun field(): String {
                val colon = text.indexOf(':', p); val len = text.substring(p, colon).toInt()
                return text.substring(colon + 1, colon + 1 + len).also { p = colon + 1 + len + 1 }
            }
            val event = word(); val cls = field(); val method = field(); word(); val msg = field()
            out += Result(event, cls.substringAfterLast('.'), method, msg)
            i = p
        }
        return out
    }

    @Test
    fun runsJunit4RulesHamcrestAndBacktickTestsOnArt() {
        val root = stage()
        val classes = root.resolve("classes")
        val jars = Files.list(root.resolve("jars")).use { s -> s.filter { it.toString().endsWith(".jar") }.toList() }
        val found = TestDiscovery.discover(listOf(classes), jars)
        val runner = Files.createDirectories(root.resolve("runner"))
        for ((name, bytes) in TestRunnerGenerator.generateAll(found)) {
            val file = runner.resolve("$name.class")
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        val output = StringBuffer()
        val io = object : ProgramIo {
            override fun stdout(text: String) { output.append(text) }
            override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        }
        val request = InterpretRunRequest(listOf(runner, classes) + jars, TestRunnerGenerator.MAIN_CLASS.replace('/', '.'))
        runBlocking { VmProgramInterpreter(peerFactory = DexPeerFactory()).run(request, io) }
        val text = output.toString()
        Log.i("TestRunnerArt", text.replace(TestProtocol.MARK, "<R>"))
        val results = parse(text)
        fun final(cls: String, method: String): Result =
            results.lastOrNull { it.cls == cls && it.method == method && it.event != TestProtocol.QUEUED && it.event != TestProtocol.STARTED }
                ?: throw AssertionError("no result for $cls.$method in:\n" + text.replace(TestProtocol.MARK, "<R>"))
        fun passed(cls: String, method: String) = final(cls, method).let { assertEquals("$cls.$method: ${it.message}", TestProtocol.PASSED, it.event) }

        // The originally reported crash: the sortedBy Comparator's peer, named after a backtick test, refused by D8.
        passed("BacktickFixture", "sources are every file, sorted")
        for (m in listOf("createsFiles", "knowsItsName", "expectedByRule", "hamcrestMatchers", "classRuleApplies")) passed("RuleFixture", m)
        assertEquals(TestProtocol.FAILED, final("RuleFixture", "failsInsideRules").event)
        assertTrue(final("RulePrivateFixture", "needsTheRule").message, "must be public" in final("RulePrivateFixture", "needsTheRule").message)
        assertTrue(text, "method-rule createsFiles on RuleFixture test=true" in text)
        assertTrue(text, "watched failsInsideRules failed" in text)
    }

    @Test
    fun reflectsOverInterpretedClassesOnArt() {
        val classes = stage().resolve("classes")
        val vm = Vm(
            source = ClassBytesSource { name -> classes.resolve("$name.class").takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } },
            policy = InterpretPolicy { it.startsWith("dev/ide/jvm/fixtures/") },
            peerFactory = DexPeerFactory(),
        )
        // What the same probes return run for real on the desktop JVM (see VmInterpreterTest's twins).
        val expected = mapOf(
            "ReflectMethods.expectedTypes" to "java.lang.String,java.lang.String",
            "ReflectMethods.superChain" to "dev.ide.jvm.fixtures.ReflectMethods\$EmptyString,java.lang.Object,null,2",
            "ReflectMethods.invokeVirtual" to "HI bob|hi ann",
            "ReflectMethods.invokeWrapsFailure" to "java.lang.IllegalStateException: boom",
            "ReflectMethods.missingMethod" to "NoSuchMethodException",
            "ReflectMembers.fields" to "[big:long:public, count:int:public, created:int:public static, name:String:public, " +
                "next:Item:protected transient, on:boolean:public, ratio:double:private volatile]|second,42,7,2.5,true,true,true,9,true,true,NoSuchFieldException",
            "ReflectMembers.methods" to "[hello@Base, own@Child, run@Child]|true|base|true",
            "ReflectMembers.generics" to "true,true,T,true,true,true,true,true,E,? extends E,T[],E",
            "ReflectMembers.annotations" to "cls3Atrue0,f7true,mB[x, y],2:1:p:0,true,false,2,true,false,true,3",
        )
        val failures = ArrayList<String>()
        for ((probe, want) in expected) {
            val (cls, method) = probe.split('.')
            val got = runCatching { vm.invokeStatic("dev/ide/jvm/fixtures/$cls", method, "()Ljava/lang/String;", emptyList()) }
                .getOrElse { "threw " + it.stackTraceToString().take(1500) }
            Log.i("TestRunnerArt", "$probe -> $got")
            if (got != want) failures += "$probe\n  want $want\n  got  $got"
        }
        assertEquals(42, vm.invokeStatic("dev/ide/jvm/fixtures/ReflectMethods", "invokeStatic", "()I", emptyList()))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
