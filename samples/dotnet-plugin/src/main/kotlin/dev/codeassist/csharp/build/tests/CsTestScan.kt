package dev.codeassist.csharp.build.tests

/**
 * Finds the tests a C# file declares, for the gutter: the methods marked `[Fact]`, `[Theory]`, `[Test]`,
 * `[TestCase]`, `[TestMethod]` or `[DataTestMethod]`, and the classes that hold them.
 *
 * Not a parser. Comments and strings are blanked first, so an attribute inside either is not taken for one;
 * then namespaces and types are found by keyword and their bodies by matching braces. That is enough to
 * name a test the way the runner does, `Namespace.Class` and the method, which is what makes a mark run the
 * test it sits beside.
 */
internal object CsTestScan {

    /** A test method: its name, where the name is, and the full name of the class holding it. */
    data class Test(val name: String, val offset: Int, val suite: String)

    /** A class with tests: its simple name, where the name is, and its full name. */
    data class Suite(val name: String, val offset: Int, val fullName: String)

    class Result(val suites: List<Suite>, val tests: List<Test>)

    private val TEST_WORDS = listOf("Fact", "Theory", "TestCase", "TestMethod", "DataTestMethod", "Test")

    private val ATTRIBUTE = Regex(
        """\[\s*(?:[\w.]+\s*(?:\([^\[\]]*\))?\s*,\s*)*(?:[\w.]+\.)?(?:Fact|Theory|TestCaseSource|TestCase|TestMethod|DataTestMethod|Test)(?:Attribute)?\s*(?:\([^\[\]]*\))?\s*(?:,[^\[\]]*)?]""",
    )
    private val NAMESPACE = Regex("""\bnamespace\s+([\w.]+)\s*([;{])""")
    private val TYPE = Regex("""\b(?:class|struct|record(?:\s+(?:class|struct))?)\s+(\w+)""")

    private class Block(val name: String, val nameOffset: Int, val open: Int, val close: Int, val isType: Boolean)

    /** Whether [text] can hold a test at all, checked before the scan proper. */
    fun mayHoldTests(text: String): Boolean = TEST_WORDS.any { "[$it" in text || " $it]" in text || ".$it" in text || "$it(" in text }

    fun scan(text: String): Result {
        val code = blank(text)
        val blocks = ArrayList<Block>()
        var fileNamespace = ""

        for (match in NAMESPACE.findAll(code)) {
            val name = match.groupValues[1]
            if (match.groupValues[2] == ";") {
                fileNamespace = name
            } else {
                val open = match.range.last
                blocks += Block(name, match.groups[1]!!.range.first, open, closeOf(code, open), isType = false)
            }
        }
        for (match in TYPE.findAll(code)) {
            val open = bodyOf(code, match.range.last + 1) ?: continue
            blocks += Block(match.groupValues[1], match.groups[1]!!.range.first, open, closeOf(code, open), isType = true)
        }

        fun suiteAt(offset: Int): Pair<String, Block>? {
            val around = blocks.filter { it.open < offset && offset < it.close }.sortedBy { it.open }
            val owner = around.lastOrNull { it.isType } ?: return null
            val names = listOf(fileNamespace).filter { it.isNotEmpty() } + around.map { it.name }
            return names.joinToString(".") to owner
        }

        val tests = LinkedHashMap<Int, Test>()
        val suites = LinkedHashMap<String, Suite>()
        for (match in ATTRIBUTE.findAll(code)) {
            val (name, offset) = methodAfter(code, match.range.last + 1) ?: continue
            val (suite, owner) = suiteAt(offset) ?: continue
            tests.putIfAbsent(offset, Test(name, offset, suite))
            suites.putIfAbsent(suite, Suite(owner.name, owner.nameOffset, suite))
        }
        return Result(suites.values.toList(), tests.values.toList())
    }

    /**
     * The name of the method declared after [from], and where it is: past any further attributes, the word
     * before the first `(`. Null when a `;`, `{` or `}` comes first, which is no method declaration.
     */
    private fun methodAfter(code: String, from: Int): Pair<String, Int>? {
        var i = from
        while (true) {
            while (i < code.length && code[i].isWhitespace()) i++
            if (i < code.length && code[i] == '[') {
                i = (code.indexOf(']', i).takeIf { it >= 0 } ?: return null) + 1
            } else {
                break
            }
        }
        var paren = i
        while (paren < code.length && code[paren] != '(') {
            if (code[paren] == ';' || code[paren] == '{' || code[paren] == '}') return null
            paren++
        }
        if (paren >= code.length) return null

        var end = paren
        while (end > i && code[end - 1].isWhitespace()) end--
        // A generic method's type parameters sit between the name and the parenthesis.
        if (end > i && code[end - 1] == '>') {
            var depth = 0
            while (end > i) {
                end--
                if (code[end] == '>') depth++
                if (code[end] == '<' && --depth == 0) break
            }
            while (end > i && code[end - 1].isWhitespace()) end--
        }
        var start = end
        while (start > i && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        if (start == end) return null
        return code.substring(start, end) to start
    }

    /** The `{` that opens the body of the type declared before [from], or null when it has none. */
    private fun bodyOf(code: String, from: Int): Int? {
        var i = from
        var parens = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> if (parens == 0) return i
                ';' -> if (parens == 0) return null
            }
            i++
        }
        return null
    }

    /** The `}` matching the `{` at [open], or the end of the text when it is not closed yet. */
    private fun closeOf(code: String, open: Int): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return code.length
    }

    /**
     * [text] with comments, strings and character literals replaced by spaces, offsets unchanged. Handles
     * `//` and block comments, ordinary, verbatim (`@"..."`) and raw (`"""..."""`) strings.
     */
    internal fun blank(text: String): String {
        val out = CharArray(text.length) { text[it] }
        fun wipe(from: Int, until: Int) {
            for (k in from until minOf(until, out.size)) if (out[k] != '\n') out[k] = ' '
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrElse(i + 1) { ' ' }
            when {
                c == '/' && next == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    wipe(i, end); i = end
                }
                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    wipe(i, end); i = end
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    var quotes = 0
                    while (i + quotes < text.length && text[i + quotes] == '"') quotes++
                    val fence = "\"".repeat(quotes)
                    val end = text.indexOf(fence, i + quotes).let { if (it < 0) text.length else it + quotes }
                    wipe(i, end); i = end
                }
                c == '"' -> {
                    val verbatim = i > 0 && (text[i - 1] == '@' || (text[i - 1] == '$' && i > 1 && text[i - 2] == '@'))
                    var j = i + 1
                    while (j < text.length) {
                        if (verbatim) {
                            if (text[j] == '"') { if (text.getOrElse(j + 1) { ' ' } == '"') j++ else break }
                        } else {
                            if (text[j] == '\\') j++ else if (text[j] == '"' || text[j] == '\n') break
                        }
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    wipe(i, end); i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && j < i + 8 && text[j] != '\'' && text[j] != '\n') { if (text[j] == '\\') j++; j++ }
                    val end = if (j < text.length && text[j] == '\'') j + 1 else i + 1
                    wipe(i, end); i = end
                }
                else -> i++
            }
        }
        return String(out)
    }
}
