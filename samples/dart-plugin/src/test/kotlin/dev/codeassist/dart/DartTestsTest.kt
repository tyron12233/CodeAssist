package dev.codeassist.dart

import dev.ide.build.TestReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DartTestsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun theScannerFindsTestsAndGroupsWithTheirFullNames() {
        val text = """
            import 'package:test/test.dart';

            void main() {
              test('adds', () => expect(1 + 1, 2));
              // test('commented out', () {});
              group('strings', () {
                test("upper", () { expect('test(not a call)', isNotNull); });
                test(r'raw ${'$'}name', () {});
                group('nested', () {
                  test('deep', () {});
                });
              });
              test('interpolated ${'$'}x', () {});
              group('dynamic ${'$'}y', () {
                test('inside', () {});
              });
              x.test('a method, not a test', () {});
            }
        """.trimIndent()
        val calls = DartTestScanner.scan(text)
        assertEquals(
            listOf("adds", "strings", "strings upper", "strings raw \$name", "strings nested", "strings nested deep"),
            calls.map { it.fullName },
        )
        assertEquals(listOf(false, true, false, false, true, false), calls.map { it.group })
        assertEquals(text.indexOf("test('adds'"), calls.first().offset)
    }

    @Test
    fun runnerEventsBecomeTestReportRecords() {
        val out = StringBuilder()
        val events = DartTestEvents { out.append(it) }
        listOf(
            """{"type":"codeassist.suite","suite":"test.calc_test"}""",
            """{"protocolVersion":"0.1.1","type":"start","time":0}""",
            """{"test":{"id":2,"name":"adds","metadata":{"skip":false,"skipReason":null}},"type":"testStart","time":6}""",
            """{"testID":2,"result":"success","skipped":false,"hidden":false,"type":"testDone","time":21}""",
            """{"test":{"id":4,"name":"strings upper","metadata":{"skip":false,"skipReason":null}},"type":"testStart","time":24}""",
            """{"testID":4,"messageType":"print","message":"hello from a test","type":"print","time":26}""",
            """{"testID":4,"error":"Expected: 'b'","stackTrace":"calc_test.dart 7:5","isFailure":true,"type":"error","time":40}""",
            """{"testID":4,"result":"failure","skipped":false,"hidden":false,"type":"testDone","time":44}""",
            """{"test":{"id":6,"name":"strings later","metadata":{"skip":true,"skipReason":"not yet"}},"type":"testStart","time":46}""",
            """{"testID":6,"result":"success","skipped":true,"hidden":false,"type":"testDone","time":47}""",
            // A second engine (a filtered run) numbers its tests from scratch.
            """{"protocolVersion":"0.1.1","type":"start","time":0}""",
            """{"test":{"id":2,"name":"again","metadata":{"skip":false,"skipReason":null}},"type":"testStart","time":1}""",
            """{"testID":2,"result":"success","skipped":false,"hidden":false,"type":"testDone","time":3}""",
            "not an event",
        ).forEach(events::line)

        val expected = TestReport.started("test.calc_test", "adds") +
            TestReport.passed("test.calc_test", "adds", 15) +
            TestReport.started("test.calc_test", "strings upper") +
            "hello from a test\n" +
            TestReport.failed("test.calc_test", "strings upper", 20, "Expected: 'b'\ncalc_test.dart 7:5") +
            TestReport.started("test.calc_test", "strings later") +
            TestReport.skipped("test.calc_test", "strings later", "not yet") +
            TestReport.started("test.calc_test", "again") +
            TestReport.passed("test.calc_test", "again", 2) +
            "not an event\n"
        assertEquals(expected, out.toString())
        assertEquals(3, events.ran)
        assertEquals(1, events.failed)
    }

    @Test
    fun aSuiteThatCannotLoadFailsAsASuite() {
        val out = StringBuilder()
        val events = DartTestEvents { out.append(it) }
        events.line("""{"type":"codeassist.suite","suite":"test.broken_test"}""")
        events.line("""{"type":"codeassist.error","error":"Bad state: no","stackTrace":"main"}""")
        assertEquals(TestReport.suiteFailed("test.broken_test", "Bad state: no\nmain"), out.toString())
        assertEquals(1, events.failed)
    }

    @Test
    fun theRunnerImportsEachSuiteAndEscapesItsNames() {
        val source = DartTestRunnerSource.generate(linkedMapOf("test.a_test" to "../../../test/a_test.dart", "test.it's" to "../../../test/it's.dart"))
        assertTrue("import '../../../test/a_test.dart' as s0;" in source, source)
        assertTrue("import '../../../test/it\\'s.dart' as s1;" in source, source)
        assertTrue("'test.a_test': () => s0.main()," in source, source)
        assertTrue("a.startsWith('\${entry.key}#')" in source, source)
    }

    @Test
    fun theFileMarkIsOnMainNotTheLineAbove() {
        val text = "import 'package:test/test.dart';\n\nvoid main() {\n}\n"
        assertEquals(text.indexOf("void main"), DartTestProvider.mainOffset(text))
        assertEquals(0, DartTestProvider.mainOffset("// no main here\n"))
    }

    @Test
    fun suitesAreDottedPathsInThePackage() {
        val file = dir.resolve("test/models/user_test.dart")
        Files.createDirectories(file.parent)
        Files.write(file, ByteArray(0))
        Files.write(dir.resolve("test/helpers.dart"), ByteArray(0))
        assertEquals(listOf(file), DartTestProvider.testFiles(dir))
        assertEquals("test.models.user_test", DartTestProvider.suiteOf(dir, file))
    }
}
