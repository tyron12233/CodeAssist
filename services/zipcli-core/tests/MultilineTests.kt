import com.zipcli.ke.domain.*
import com.zipcli.ke.domain.commands.*
import com.zipcli.ke.domain.script.*
import com.zipcli.ke.fs.*
import com.zipcli.ke.zip.*
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Focused tests for multi-line `find` / `replace` (exact, --reindent, --regex, CRLF, scripts).
 * Standalone JVM program, same style as ScenarioTests.kt:  prints FAIL lines and a PASS/FAIL total.
 */
var pass = 0; var fail = 0
val failures = ArrayList<String>()

fun esc(s: String) = s.replace("\r", "<CR>").replace("\t", "<T>").replace("\u2028", "<LS>")

fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) pass++ else { fail++; failures += name; println("FAIL: $name  $extra") }
}

class Io : ScriptIo {
    val out = ArrayList<String>(); val err = ArrayList<String>()
    override fun print(lines: List<String>, kind: LineKind) {
        if (kind == LineKind.INFO && lines.size == 1 && lines[0].contains(" commands OK")) return
        if (kind == LineKind.ERROR) err += lines else out += lines
    }
    override suspend fun confirm(prompt: String): Boolean = true
    override fun clear() {}
    override fun openEditor(path: String, content: String) {}
    override suspend fun requestSafPermission() {}
    override fun progress(message: String?) {}
    override fun cwdChanged(cwd: String) {}
    fun all() = (out + err).joinToString("\n")
    fun reset() { out.clear(); err.clear() }
}

fun main() {
    val root = File.createTempFile("zcli-ml", "").also { it.delete(); it.mkdirs() }
    val fs = NioFileSystem(mapOf("/home" to root))
    lateinit var reg: CommandRegistry
    val specs = buildCoreCommands { reg } + buildContentCommands() + buildArchiveCommands(Zipper(fs), Unzipper(fs)) + buildScriptCommands()
    reg = CommandRegistry(specs)
    val parser = CommandParser(reg)
    val io = Io()
    val runner = ScriptRunner(reg, parser, fs, io)
    val ses = ShellSession("/home")
    fun run(s: String) { io.reset(); runBlocking { runner.run(s, ses) } }
    fun file(p: String) = File(root, p)
    fun w(p: String, t: String) { file(p).parentFile.mkdirs(); file(p).writeText(t) }
    fun rd(p: String) = file(p).readText()

    /** Writes [content] to a file, runs [cmd], and checks the file ends up as [expected]. */
    fun case(name: String, content: String, cmd: String, expected: String, file: String = "t.txt") {
        w(file, content)
        run(cmd)
        val got = rd(file)
        check(name, got == expected, "\n   cmd:      ${esc(cmd)}\n   expected: ${esc(expected)}\n   got:      ${esc(got)}\n   out: ${io.all()}")
    }

    val LF = "\n"; val CRLF = "\r\n"
    fun crlf(s: String) = s.replace("\n", "\r\n")

    // ═════════════════ A. exact (literal) multi-line ═════════════════
    case("A1 basic 2-line", "a\nb\nc\n", "replace t.txt \"a\nb\" \"X\nY\"", "X\nY\nc\n")
    case("A2 trailing newline in pattern", "a\nb\nc\n", "replace t.txt \"a\nb\n\" \"X\n\"", "X\nc\n")
    case("A3 trailing newline, CRLF file", crlf("a\nb\nc\n"), "replace t.txt \"a\nb\n\" \"X\n\"", crlf("X\nc\n"))
    case("A4 delete whole lines", "keep\ndrop1\ndrop2\nkeep2\n", "replace t.txt \"drop1\ndrop2\n\" \"\"", "keep\nkeep2\n")
    case("A5 CRLF: inserted breaks follow file", crlf("a\nb\n"), "replace t.txt \"a\nb\" \"a\nm\nb\"", crlf("a\nm\nb\n"))
    case("A6 mixed EOL: untouched lines identical", "one\r\ntwo\r\nthree\nfour\nfive\n", "replace t.txt \"four\nfive\" \"4\n5\"", "one\r\ntwo\r\nthree\n4\n5\n")
    case("A7 file without final newline", "a\nb\nc", "replace t.txt \"b\nc\" \"B\nC\"", "a\nB\nC")
    case("A8 non-overlapping count", "aa\naa\naa\naa\n", "replace t.txt \"aa\naa\" \"X\"", "X\nX\n")
    case("A9 dollar/backslash literal in replacement (single quotes are POSIX-literal: \\\\ stays two backslashes)", "a\nb\n", "replace t.txt \"a\nb\" '\$1 \\\\ \${x}\n\$2'", "\$1 \\\\ \${x}\n\$2\n")
    case("A10 ignore case multi-line", "Foo\nBAR\n", "replace t.txt \"foo\nbar\" \"x\ny\" -i", "x\ny\n")
    case("A11 tab escape in pattern", "a\n\tb\n", "replace t.txt \"a\n\\tb\" \"z\"", "z\n")
    case("A12 unicode + emoji kept", "héllo\n🙂 wörld\nend\n", "replace t.txt \"héllo\n🙂 wörld\" \"ok\"", "ok\nend\n")
    w("o.txt", "x\r\ny\r\n"); w("n.txt", "X\r\nY\r\n")
    case("A13 pattern from CRLF files", "x\ny\nz\n", "replace t.txt --old-file o.txt --new-file n.txt", "X\nY\nz\n")
    case("A14 mid-line start spanning lines", "call(a,\n     b)\n", "replace t.txt \"a,\n     b\" \"a, b\"", "call(a, b)\n")
    case("A15 whole-word multi-line", "cat\ndog\ncatalog\ndogma\n", "replace t.txt \"cat\ndog\" \"X\" -w", "X\ncatalog\ndogma\n")
    case("A16 empty lines inside block", "a\n\nb\n", "replace t.txt \"a\n\nb\" \"X\"", "X\n")
    case("A17 BOM preserved", "\uFEFFfoo\nbar\nbaz\n", "replace t.txt \"bar\nbaz\" \"B\"", "\uFEFFfoo\nB\n")

    // ═════════════════ B. --reindent ═════════════════
    val kt = "class A {\n    fun f() {\n        if (x) {\n            go()\n        }\n    }\n}\n"
    case("B1 trailing newline keeps next line indent", "    a()\n    b()\n    c()\n",
        "replace t.txt \"a()\nb()\n\" \"A()\nB()\n\" --reindent", "    A()\n    B()\n    c()\n")
    case("B2 trailing newline, CRLF", crlf("    a()\n    b()\n    c()\n"),
        "replace t.txt \"a()\nb()\n\" \"A()\nB()\n\" --reindent", crlf("    A()\n    B()\n    c()\n"))
    case("B3 spaces -> tabs", "\tif (x) {\n\t\tgo()\n\t}\n", "replace t.txt \"if (x) {\n    go()\n}\" \"if (y) {\n    a()\n}\" --reindent",
        "\tif (y) {\n\t\ta()\n\t}\n")
    case("B4 tabs typed, spaces file", "    if (x) {\n        go()\n    }\n", "replace t.txt \"if (x) {\n\\tgo()\n}\" \"if (x) {\n\\tgo()\n\\tmore()\n}\" --reindent",
        "    if (x) {\n        go()\n        more()\n    }\n")
    case("B5 2-space file, 4-space typed", "a:\n  b:\n    if (x) {\n      go()\n    }\n",
        "replace t.txt \"if (x) {\n    go()\n}\" \"if (x) {\n    go()\n    more()\n}\" --reindent",
        "a:\n  b:\n    if (x) {\n      go()\n      more()\n    }\n")
    case("B6 two matches at different depths", "if (x) {\n  go()\n}\nfn() {\n        if (x) {\n          go()\n        }\n}\n",
        "replace t.txt \"if (x) {\ngo()\n}\" \"if (x) {\n  stop()\n}\" --reindent",
        "if (x) {\n  stop()\n}\nfn() {\n        if (x) {\n          stop()\n        }\n}\n")
    case("B7 blank line inside, whitespace-only in file", "    a()\n   \n    b()\n", "replace t.txt \"a()\n\nb()\" \"c()\" --reindent", "    c()\n")
    case("B8 blank line in replacement has no trailing spaces", "    a()\n", "replace t.txt \"a()\" \"x()\n\ny()\" --reindent", "    x()\n\n    y()\n")
    case("B9 CRLF + reindent", crlf("    if (x) {\n        go()\n    }\n"),
        "replace t.txt \"if (x) {\ngo()\n}\" \"if (y) {\n    go()\n}\" --reindent", crlf("    if (y) {\n        go()\n    }\n"))
    case("B10 dedent in replacement", "        a()\n        b()\n", "replace t.txt \"a()\nb()\" \"x()\n    y()\" --reindent",
        "        x()\n            y()\n")
    case("B11 ignore-case + reindent", "    IF (X) {\n        GO()\n    }\n", "replace t.txt \"if (x) {\ngo()\n}\" \"ok()\" --reindent -i", "    ok()\n")
    case("B12 U+2028 inside a line is not a line start", "s = \"a\u2028    if (x) {\n    go()\n    }\"\n",
        "replace t.txt \"if (x) {\ngo()\n}\" \"Z\" --reindent", "s = \"a\u2028    if (x) {\n    go()\n    }\"\n")
    case("B13 no change without flag + tip", "\tif (x) {\n\t\tgo()\n\t}\n", "replace t.txt \"if (x) {\n    go()\n}\" \"z\"", "\tif (x) {\n\t\tgo()\n\t}\n")
    check("B13b tip printed", io.out.any { it.contains("--reindent") }, io.all())
    case("B14 reindent keeps text after the block on its line", "    f(a,\n      b) // tail\n", "replace t.txt \"f(a,\nb)\" \"g(a, b)\" --reindent", "    g(a, b) // tail\n")
    case("B15 reindent whole-word", "    cat()\n    dog()\n    catalog()\n", "replace t.txt \"cat()\ndog()\" \"X()\" --reindent -w", "    X()\n    catalog()\n")

    // ═════════════════ C. --regex ═════════════════
    case("C1 \\n escape typed unquoted, LF", "a\nb\nc\n", "replace t.txt --regex a\\nb X", "X\nc\n")
    case("C2 \\n escape typed unquoted, CRLF", crlf("a\nb\nc\n"), "replace t.txt --regex a\\nb X", "X" + CRLF + "c" + CRLF)
    case("C3 literal LF in quoted regex, CRLF", crlf("a\nb\nc\n"), "replace t.txt --regex \"a\nb\" X", "X" + CRLF + "c" + CRLF)
    case("C4 \$ anchor on CRLF file", crlf("foo\nbar\n"), "replace t.txt --regex 'foo\$' 'X'", crlf("X\nbar\n"))
    case("C5 ^ anchor, comment out lines, CRLF", crlf("a\nb\n"), "replace t.txt --regex '^' '// '", crlf("// a\n// b\n"))
    case("C6 dotall across CRLF", crlf("start\nmid\nend\ntail\n"), "replace t.txt --regex --dotall 'start.*end' 'X'", "X" + CRLF + "tail" + CRLF)
    case("C7 [^\\n]* on CRLF does not eat the CR", crlf("k=1\nk=2\n"), "replace t.txt --regex 'k=[^\\n]*' 'v'", crlf("v\nv\n"))
    case("C8 groups across lines", "name: a\nval: b\n", "replace t.txt --regex 'name: (\\w+)\nval: (\\w+)' '\$2=\$1'", "b=a\n")
    case("C9 named group", "x\ny\n", "replace t.txt --regex '(?<p>x)\ny' '\${p}!'", "x!\n")
    case("C10 newline in replacement via quotes, CRLF", crlf("a b\n"), "replace t.txt --regex '(a) (b)' '\$1\n\$2'", crlf("a\nb\n"))
    case("C11 \\n typed unquoted in REPLACEMENT", "a b\n", "replace t.txt --regex '(a) (b)' \$1\\n\$2", "a\nb\n")
    case("C12 escaped dollar stays literal", "a\n", "replace t.txt --regex '(a)' '\\\$1'", "\$1\n")
    case("C13 -w with regex", "xcat cat\n", "replace t.txt --regex 'c\\w*t' 'X' -w", "xcat X\n")
    case("C14 \\s+ joins lines", "a\n   b\n", "replace t.txt --regex 'a\\s+b' 'a b'", "a b\n")
    case("C15 invalid group reported, file untouched", "a\n", "replace t.txt --regex '(a)' '\$3'", "a\n")
    check("C15b error shown", io.err.isNotEmpty() || io.out.any { it.contains("No group") || it.contains("failed") }, io.all())
    case("C16 invalid regex message", "a\n", "replace t.txt --regex '(a' 'x'", "a\n")
    check("C16b message", io.err.any { it.contains("Invalid pattern") }, io.all())
    case("C17 \\r?\\n explicit", crlf("a\nb\n"), "replace t.txt --regex 'a\\r?\\nb' 'X'", "X" + CRLF)
    case("C18 \\Q..\\E untouched by newline translation", "a.b\nc\n", "replace t.txt --regex '\\Qa.b\\E\nc' 'X'", "X\n")
    case("C19 escaped backslash then n is NOT a newline", "a\\nb\n", "replace t.txt --regex 'a\\\\nb' 'X'", "X\n")

    // ═════════════════ D. find ═════════════════
    w("f.txt", "x\nfoo\nbar\nbaz\n"); run("find 'foo\nbar' f.txt")
    check("D1 multi-line find start line", io.out.any { it.startsWith("/home/f.txt:2:") } && io.out.any { it.contains("│ bar") }, io.all())
    w("f.txt", crlf("x\nfoo\nbar\n")); run("find --regex foo\\nbar f.txt")
    check("D2 find --regex with typed \\n spans lines", io.out.any { it.startsWith("/home/f.txt:2:") }, io.all())
    run("find --regex 'foo\nbar' f.txt")
    check("D3 find --regex literal LF on CRLF", io.out.any { it.startsWith("/home/f.txt:2:") }, io.all())
    w("f.txt", "    a()\n    b()\n"); run("find 'a()\nb()' f.txt --reindent")
    check("D4 find --reindent", io.out.any { it.startsWith("/home/f.txt:1:") && it.contains("    a()") }, io.all())
    w("f.txt", "1\n2\n3\n4\n5\n6\n7\n"); run("find '1\n2\n3\n4\n5\n6' f.txt")
    check("D5 long match is truncated with count", io.out.any { it.contains("more line") }, io.all())
    w("f.txt", "a\nb\na\nb\na\nb\n"); run("find 'a\nb' f.txt --max 2")
    check("D6 --max respected", io.out.count { it.startsWith("/home/f.txt:") } == 2 && io.out.any { it.contains("stopped at 2") }, io.all())
    w("f.txt", "Foo\nBar\n"); run("find --regex '\\S+\nbar' f.txt")
    check("D7 smart-case ignores regex escape letters", io.out.any { it.startsWith("/home/f.txt:1:") }, io.all())
    w("f.txt", "A\nb\n"); run("find 'a\nb' f.txt")
    check("D8 smart-case lowercase pattern insensitive", io.out.any { it.startsWith("/home/f.txt:1:") }, io.all())
    // line numbers on a big file are right and fast
    val big = StringBuilder(); for (i in 1..150_000) big.append("line $i\nmark\n")
    w("big.txt", big.toString())
    val bigLines = big.toString().lines()
    val t0 = System.nanoTime(); run("find '1\nmark' big.txt --max 10000"); val ms = (System.nanoTime() - t0) / 1_000_000
    val hits = io.out.filter { it.startsWith("/home/big.txt:") }
    check("D9 perf: 10k multi-line hits fast (${ms}ms)", ms < 3000 && hits.size == 10000, "hits=${hits.size} " + io.out.takeLast(1))
    val lastNo = hits.lastOrNull()?.removePrefix("/home/big.txt:")?.substringBefore(':')?.toIntOrNull() ?: -1
    check("D9b last hit line number is right", lastNo > 0 && bigLines[lastNo - 1].endsWith("1") && bigLines[lastNo] == "mark", "lastNo=$lastNo")
    run("find 'line 100\nmark' big.txt"); check("D10 exact line number on big file", io.out.any { it.startsWith("/home/big.txt:199:") }, io.all())

    // ═════════════════ E. multi-file / dry-run ═════════════════
    w("d/a.kt", "    if (x) {\n        go()\n    }\n"); w("d/b.kt", crlf("if (x) {\n    go()\n}\n")); w("d/c.bin", "\u0000if (x) {\n go()\n}\n")
    val binBefore = file("d/c.bin").readBytes()
    run("replace d \"if (x) {\ngo()\n}\" \"if (y) {\n    stop()\n}\" -r --reindent")
    check("E1 recursive reindent a.kt", rd("d/a.kt") == "    if (y) {\n        stop()\n    }\n", esc(rd("d/a.kt")))
    check("E2 recursive keeps CRLF b.kt", rd("d/b.kt") == crlf("if (y) {\n    stop()\n}\n"), esc(rd("d/b.kt")))
    check("E3 binary untouched", file("d/c.bin").readBytes().contentEquals(binBefore), io.all())
    w("e.txt", "a\nb\nc\n"); run("replace e.txt 'a\nb' 'X\nY' --dry-run")
    check("E4 dry-run shows - and +", io.out.any { it.trim() == "- a" } && io.out.any { it.trim() == "+ X" } && rd("e.txt") == "a\nb\nc\n", io.all())
    check("E5 dry-run says would replace 1", io.out.any { it.contains("Would replace 1") }, io.all())
    w("e.txt", "a\nb\n"); run("replace e.txt 'a\nb' 'Q' --backup"); w("e.txt", "a\nb\nsecond\n"); run("replace e.txt 'a\nb' 'R' --backup")
    check("E6 backup keeps FIRST original", rd("e.txt.bak") == "a\nb\n", rd("e.txt.bak"))
    // big file replace is quick
    val big2 = StringBuilder(); for (i in 1..100_000) big2.append("    if (x) {\n        go()\n    }\n")
    w("big2.kt", big2.toString())
    val t1 = System.nanoTime(); run("replace big2.kt \"if (x) {\ngo()\n}\" \"if (y) {\n    stop()\n}\" --reindent"); val ms2 = (System.nanoTime() - t1) / 1_000_000
    check("E7 perf: 100k reindent replacements (${ms2}ms)", ms2 < 8000 && rd("big2.kt").startsWith("    if (y) {\n        stop()\n    }\n"), io.all())

    // ═════════════════ F. scripts / quoting ═════════════════
    w("s.txt", "a; b # c && d || e\nnext\n")
    runBlocking { fs.writeText("/home/m.zcli", "replace s.txt \"a; b # c && d || e\nnext\" \\\n   \"done\\nok\"\necho finished\n") }
    run("run m.zcli"); check("F1 separators/comments inside quoted block are literal", rd("s.txt") == "done\nok\n" && io.out.contains("finished"), esc(rd("s.txt")) + io.all())
    w("s.txt", "x\ny\n")
    runBlocking { fs.writeText("/home/m2.zcli", "set P='x\ny'\nreplace s.txt \"\$P\" Z\n") }
    run("run m2.zcli"); check("F2 variable holding a multi-line pattern", rd("s.txt") == "Z\n", esc(rd("s.txt")) + io.all())
    w("s.txt", "a\nb\n")
    run("replace s.txt 'a\nb' 'c' && cat s.txt"); check("F3 chained with && after multi-line quotes", io.out.contains("c"), io.all())
    w("s.txt", "a\nb\n"); run("replace s.txt 'a\nb' 'c' # trailing comment"); check("F4 trailing comment after quotes", rd("s.txt") == "c\n", io.all())
    w("s.txt", "q\n"); run("replace s.txt \"q\" \"say \\\"hi\\\"\nthere\""); check("F5 escaped quotes + multi-line replacement", rd("s.txt") == "say \"hi\"\nthere\n", esc(rd("s.txt")))

    // ═════════════════ G. autocomplete around patterns ═════════════════
    val ac = Autocomplete(reg, fs)
    w("zebra.txt", "z")
    fun comp(s: String) = runBlocking { ac.complete(s, "/home", emptyList()) }
    check("G1 Tab inside multi-line quoted pattern leaves it alone", comp("replace t.txt \"zeb\nmore").newText == null, comp("replace t.txt \"zeb\nmore").toString())
    check("G2 Tab in the <old> pattern of replace does not become a filename", comp("replace t.txt \"zeb").newText == null, comp("replace t.txt \"zeb").toString())
    check("G3 Tab in the <new> text of replace does not become a filename", comp("replace t.txt old zeb").newText == null, comp("replace t.txt old zeb").toString())
    check("G4 replace still completes its path argument", comp("replace zeb").newText == "replace zebra.txt ", comp("replace zeb").toString())
    check("G5 find completes the path (2nd positional)", comp("find x zeb").newText == "find x zebra.txt ", comp("find x zeb").toString())
    check("G6 find does not complete its pattern", comp("find zeb").newText == null, comp("find zeb").toString())
    check("G7 --old-file value is a path", comp("replace t.txt --old-file zeb").newText == "replace t.txt --old-file zebra.txt ", comp("replace t.txt --old-file zeb").toString())
    check("G8 flag value then path still works", comp("find --name '*.kt' x zeb").newText == "find --name '*.kt' x zebra.txt ", comp("find --name '*.kt' x zeb").toString())
    check("G9 other commands unaffected", comp("cat zeb").newText == "cat zebra.txt ", comp("cat zeb").toString())



    // ═════════════════ H. round 2: anchors, classes, BOM, single-line --reindent ═════════════════
    case("H1 \$ adds no phantom match after the final newline", "a\nb\n", "replace t.txt --regex '\$' ';'", "a;\nb;\n")
    case("H2 \$ on CRLF file", crlf("a\nb\n"), "replace t.txt --regex '\$' ';'", crlf("a;\nb;\n"))
    case("H3 \$ on file without final newline", "a\nb", "replace t.txt --regex '\$' ';'", "a;\nb;")
    case("H4 ^ works on line 1 of a BOM file", "\uFEFFfoo\nbar\n", "replace t.txt --regex '^(\\w+)' '<\$1>'", "\uFEFF<foo>\n<bar>\n")
    case("H5 ^ ignores U+2028 inside a line", "x\u2028y\n", "replace t.txt --regex '^y' 'Z'", "x\u2028y\n")
    case("H6 ^ has no phantom match after the final newline", "a\n", "replace t.txt --regex '^' '#'", "#a\n")
    case("H7a collapse blank lines (LF)", "a\n\n\n\nb\n", "replace t.txt --regex '\\n{3,}' '\\n\\n'", "a\n\nb\n")
    case("H7b collapse blank lines (CRLF)", crlf("a\n\n\n\nb\n"), "replace t.txt --regex '\\n{3,}' '\\n\\n'", crlf("a\n\nb\n"))
    case("H8 lookbehind on a line break, CRLF", crlf("a\nb\n"), "replace t.txt --regex '(?<=\\n)b' 'X'", crlf("a\nX\n"))
    case("H9 escaped ] inside a class with \\n", "a]\nb\n", "replace t.txt --regex 'a[\\]\\n]+b' 'X'", "X\n")
    case("H10 [ \\t\\n]+ crosses CRLF breaks", crlf("a\n  b\n"), "replace t.txt --regex 'a[ \\t\\n]+b' 'X'", crlf("X\n"))
    case("H11 \\Q..\\E with a literal line break, CRLF", crlf("a.b\nc\n"), "replace t.txt --regex '\\Qa.b\nc\\E' 'X'", crlf("X\n"))
    case("H14 identical replacement reports nothing", "a\nb\n", "replace t.txt 'a\nb' 'a\nb'", "a\nb\n")
    check("H14b message", io.out.any { it.startsWith("No replacements") }, io.all())
    case("H15 --reindent on line 1 of a BOM file", "\uFEFFusing A;\nusing B;\nclass C {}\n", "replace t.txt \"using A;\nusing B;\" \"using X;\" --reindent", "\uFEFFusing X;\nclass C {}\n")
    w("o.txt", "a\nb\n\n"); w("n.txt", "X\n\n")
    case("H16 pattern files with a trailing blank line = whole lines", "q\n  a\n  b\nz\n", "replace t.txt --old-file o.txt --new-file n.txt --reindent", "q\n  X\nz\n")
    case("H17 tabs file, tabs typed", "\tif (x) {\n\t\tgo()\n\t}\n", "replace t.txt \"if (x) {\n\\tgo()\n}\" \"if (y) {\n\\tgo()\n\\tstop()\n}\" --reindent", "\tif (y) {\n\t\tgo()\n\t\tstop()\n\t}\n")
    case("H18 tabs file, 2-space typed", "\tif (x) {\n\t\tgo()\n\t}\n", "replace t.txt \"if (x) {\n  go()\n}\" \"if (y) {\n  go()\n  stop()\n}\" --reindent", "\tif (y) {\n\t\tgo()\n\t\tstop()\n\t}\n")
    case("H19 one-line pattern + --reindent, tabs file", "\tfoo()\n", "replace t.txt \"foo()\" \"a()\n\\tb()\" --reindent", "\ta()\n\t\tb()\n")
    case("H20 one-line pattern mid-line + --reindent", "    x = foo(1)\n", "replace t.txt \"foo(1)\" \"foo(\n    1\n)\" --reindent", "    x = foo(\n        1\n    )\n")
    case("H21 one-line pattern + --reindent, CRLF", crlf("  a\n"), "replace t.txt \"a\" \"b\nc\" --reindent", crlf("  b\n  c\n"))
    case("H21b one-line pattern + --reindent, single-line replacement untouched", "  a\n", "replace t.txt \"a\" \"b\" --reindent", "  b\n")
    case("H22 --reindent with -w", "    a()\n    foobar()\n    a()\n    foo()\n", "replace t.txt \"a()\nfoo\" \"X\" --reindent -w", "    a()\n    foobar()\n    X()\n")
    w("t.txt", crlf("x\ny\n")); run("replace t.txt 'x\ny' 'p\nq' --dry-run")
    check("H23 dry-run preview shows no CR", io.out.none { '\r' in it } && io.out.any { it.trim() == "+ p" }, io.all())
    w("f.txt", "foo\nxfoo\nfoo\n"); run("find --regex '^foo\$' f.txt")
    check("H25 find --regex ^foo\$ line mode", io.out.count { it.startsWith("/home/f.txt:") } == 2, io.all())
    case("H26 group + \$ anchor on CRLF", crlf("ab\ncd\n"), "replace t.txt --regex '(\\w+)\$' '[\$1]'", crlf("[ab]\n[cd]\n"))
    case("H27 . does not eat the CR", crlf("abc\nabd\n"), "replace t.txt --regex 'a.*' 'X'", crlf("X\nX\n"))
    w("g1.kt", "a\nb\n"); w("g2.kt", crlf("a\nb\n")); w("g3.txt", "a\nb\n")
    run("replace *.kt 'a\nb' 'c'")
    check("H28 wildcard path with a multi-line pattern", rd("g1.kt") == "c\n" && rd("g2.kt") == crlf("c\n") && rd("g3.txt") == "a\nb\n", io.all())
    case("H30 pasted CRLF command text still matches an LF file", "a\nb\nc\n", "replace t.txt \"a\r\nb\" \"X\"", "X\nc\n")
    w("f.txt", "FOO\n"); run("find --regex '(?<Name>foo)' f.txt")
    check("H13 smart-case ignores group names", io.out.any { it.startsWith("/home/f.txt:1:") }, io.all())
    w("f.txt", "a\nb\n"); run("find --regex '(?s)a.b' f.txt")
    check("H12 (?s) makes find scan across lines", io.out.any { it.startsWith("/home/f.txt:1:") } && io.out.any { it.contains("│ b") }, io.all())

    println("\nPASS=$pass FAIL=$fail")
    if (failures.isNotEmpty()) println("FAILED: " + failures.joinToString(", "))
    root.deleteRecursively()
}
