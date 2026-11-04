package dev.codeassist.lspdemo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CalcTest {

    private val sample = """
        # the area of a rectangle
        let width = 4
        let height = width * 2
        print max(width, height) * 3
    """.trimIndent()

    @Test
    fun evaluatesAndResolves() {
        val a = CalcAnalysis(sample)
        assertEquals(emptyList<Problem>(), a.problems)
        assertEquals(8.0, a.declarations.getValue("height").value)
        assertEquals(24.0, a.values[3])
        assertEquals("width", a.symbolAt(2, 14))
        assertEquals(3, a.references("width").size)
        assertEquals(listOf("max"), a.calls.map { it.first })
    }

    @Test
    fun reportsTheProblemsTheServerPublishes() {
        val a = CalcAnalysis("let a = b + 1\nlet c = 2\nlet c = 3\nprint 1 / 0\nprint max(1)\nlet = 4\nprint a")
        val codes = a.problems.map { it.code to it.span.line }
        assertTrue(("calc.undefined" to 0) in codes, codes.toString())
        assertTrue(("calc.redefined" to 2) in codes, codes.toString())
        assertTrue(("calc.divZero" to 3) in codes, codes.toString())
        assertTrue(("calc.arity" to 4) in codes, codes.toString())
        assertTrue(("calc.syntax" to 5) in codes, codes.toString())
        assertTrue(("calc.unused" to 1) in codes, codes.toString())
        assertEquals("b", a.problems.first { it.code == "calc.undefined" }.name)
    }

    @Test
    fun testStatementsEvaluateBothSides() {
        val a = CalcAnalysis("let w = 4\ntest doubles: w * 2 is 8\ntest wrong: w + 1 is 6\ntest broken: nope is 1")
        assertEquals(listOf("doubles" to true, "wrong" to false, "broken" to false), a.tests.map { it.name to it.passed })
        assertEquals(5.0, a.tests[1].actual)
        assertNull(a.tests[2].actual)
        assertEquals("test doubles: w * 2 is 8", CalcAnalysis("test doubles :w*2 is 8").formatted(0))
    }

    @Test
    fun theTestProviderMarksEachTest() {
        val text = "# tests\ntest one: 1 is 1\ntest two: 2 is 3"
        val marks = CalcTestProvider.tests(FakeModule, "/p/src/a.calc", text)
        assertEquals(listOf("a.calc", "one", "two"), marks.map { it.name })
        assertEquals(listOf("src.a", "src.a#one", "src.a#two"), marks.map { it.target })
        assertEquals(text.indexOf("one"), marks[1].offset)
    }

    @Test
    fun formatsToCanonicalSpacing() {
        val a = CalcAnalysis("let x=max(1,-2)*( 3+4 )  # note\nprint -x")
        assertEquals("let x = max(1, -2) * (3 + 4) # note", a.formatted(0))
        assertNull(a.formatted(1))
    }

    @Test
    fun theServerAnswersOverTheProtocol() {
        val uri = "file:///p/a.calc"
        val input = frames(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""",
            """{"jsonrpc":"2.0","method":"initialized","params":{}}""",
            """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"$uri","languageId":"calc","version":1,"text":"let x = y\nprint x"}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"$uri"},"position":{"line":1,"character":6}}}""",
            """{"jsonrpc":"2.0","id":3,"method":"textDocument/completion","params":{"textDocument":{"uri":"$uri"},"position":{"line":1,"character":0}}}""",
            """{"jsonrpc":"2.0","id":4,"method":"shutdown"}""",
        )
        val out = ByteArrayOutputStream()
        CalcServer(input, out).run()
        val messages = parse(out.toByteArray())

        val capabilities = messages.first { it["id"]?.jsonPrimitive?.content == "1" }["result"]!!.jsonObject["capabilities"]!!.jsonObject
        assertTrue("semanticTokensProvider" in capabilities)

        val published = messages.first { it["method"]?.jsonPrimitive?.content == "textDocument/publishDiagnostics" }
        val diagnostic = published["params"]!!.jsonObject["diagnostics"]!!.jsonArray.first().jsonObject
        assertEquals("'y' is not defined", diagnostic["message"]!!.jsonPrimitive.content)

        val hover = messages.first { it["id"]?.jsonPrimitive?.content == "2" }["result"]!!.jsonObject
        assertTrue("let x = y" in hover["contents"]!!.jsonObject["value"]!!.jsonPrimitive.content)

        val labels = messages.first { it["id"]?.jsonPrimitive?.content == "3" }["result"]!!.jsonObject["items"]!!.jsonArray
            .map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertTrue(labels.containsAll(listOf("let", "print", "x", "max")), labels.toString())
    }

    private fun frames(vararg bodies: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        for (b in bodies) {
            val bytes = b.toByteArray()
            out.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun parse(bytes: ByteArray): List<JsonObject> {
        val text = String(bytes)
        val out = ArrayList<JsonObject>()
        var i = 0
        while (true) {
            val h = text.indexOf("Content-Length: ", i)
            if (h < 0) break
            val end = text.indexOf("\r\n\r\n", h)
            val n = text.substring(h + 16, end).trim().toInt()
            out += Json.parseToJsonElement(text.substring(end + 4, end + 4 + n)).jsonObject
            i = end + 4 + n
        }
        return out
    }
}

/** A module at `/p`: the provider reads only its directory. */
private val FakeModule: dev.ide.model.Module = java.lang.reflect.Proxy.newProxyInstance(
    CalcTest::class.java.classLoader, arrayOf(dev.ide.model.Module::class.java),
) { _, method, _ ->
    when (method.name) {
        "getDir" -> java.lang.reflect.Proxy.newProxyInstance(
            CalcTest::class.java.classLoader, arrayOf(dev.ide.vfs.VirtualFile::class.java),
        ) { _, m, _ -> if (m.name == "getPath") "/p" else throw UnsupportedOperationException(m.name) }
        else -> throw UnsupportedOperationException(method.name)
    }
} as dev.ide.model.Module
