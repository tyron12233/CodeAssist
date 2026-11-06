// Standalone JVM program (like ScenarioTests): covers the DEV_RECOMMENDATIONS fixes.
// Run: kotlinc <domain sources> RecommendationTests.kt && java RecommendationTestsKt
import com.zipcli.ke.domain.*
import com.zipcli.ke.domain.commands.*
import com.zipcli.ke.domain.script.*
import com.zipcli.ke.fs.*
import com.zipcli.ke.zip.*
import kotlinx.coroutines.runBlocking
import java.io.File

var pass = 0; var fail = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) pass++ else { fail++; println("FAIL: $name $extra") }
}

class Io(var answer: Boolean = true, val prompt: Boolean = true) : ScriptIo {
    val out = ArrayList<String>(); val err = ArrayList<String>(); val info = ArrayList<String>(); var confirms = 0
    override val canPrompt: Boolean get() = prompt
    override fun print(lines: List<String>, kind: LineKind) { when (kind) { LineKind.ERROR -> err += lines; LineKind.INFO -> info += lines; else -> out += lines } }
    override suspend fun confirm(prompt: String): Boolean { confirms++; return answer }
    override fun clear() {}
    override fun openEditor(path: String, content: String) {}
    override suspend fun requestSafPermission() {}
    override fun progress(message: String?) {}
    override fun cwdChanged(cwd: String) {}
    fun all() = (out + err + info).joinToString("\n")
    fun reset() { out.clear(); err.clear(); info.clear(); confirms = 0 }
}

class Env(promptable: Boolean = true, history: () -> List<String> = { emptyList() }, extra: List<CommandSpec> = emptyList(), transport: String = "terminal") {
    val root: File = File.createTempFile("zcli", "").also { it.delete(); it.mkdirs() }
    val fs = NioFileSystem(mapOf("/home" to root))
    lateinit var reg: CommandRegistry
    val io = Io(prompt = promptable)
    val ses = ShellSession("/home")
    val runner: ScriptRunner
    init {
        val specs = buildCoreCommands { reg } + buildContentCommands() + buildArchiveCommands(Zipper(fs), Unzipper(fs)) + buildScriptCommands() + extra
        reg = CommandRegistry(specs)
        runner = ScriptRunner(reg, CommandParser(reg), fs, io, transport, history)
    }
    fun run(s: String): ScriptRunner.Result = runBlocking { runner.run(s, ses) }
    fun file(p: String) = File(root, p.removePrefix("/home/"))
    fun text(p: String) = file(p).readText()
}

fun main() {
    // ───────────── 1.1 find --regex works; construct-only coverage of the smart-case scanner ─────────────
    run {
        val e = Env()
        e.run("update a.txt -c 'Hello World\nfoo 42\nbar'")
        for (pat in listOf("foo\\s+\\d+", "\\p{Lu}\\w+", "\\pL+", "\\p{Alpha}{2}", "(?<Name>fo+)", "\\k<Name>", "a}b", "\\Qx}\\E", "^bar$", "[A-Z]", "(?s)foo.*", "\\\\", "x{2,3}")) {
            val r = runCatching { BlockMatcher.smartCaseIgnore(pat, true) }
            check("smartCaseIgnore never throws: $pat", r.isSuccess, r.exceptionOrNull().toString())
        }
        check("smart case ignores escape letters", BlockMatcher.smartCaseIgnore("\\S+\\d", true))
        check("smart case sees a real capital", !BlockMatcher.smartCaseIgnore("\\S+Foo", true))
        check("smart case ignores group names", BlockMatcher.smartCaseIgnore("(?<Name>fo+)", true))
        check("smart case: \\p{Lu} is syntax", BlockMatcher.smartCaseIgnore("\\p{Lu}x", true))
        for (cmd in listOf("find --regex 'foo\\s+\\d+' a.txt", "find -r --regex '^bar$' /home", "find --regex 'b..' a.txt --max 5", "find --regex '\\p{Lu}\\w+' a.txt --name '*.txt' -r")) {
            e.io.reset(); val r = e.run(cmd)
            check("find --regex works: $cmd", r.status == 0 && e.io.err.isEmpty() && e.io.out.any { it.contains("a.txt:") }, e.io.all())
        }
        e.io.reset(); e.run("find --regex '(' a.txt")
        check("bad user regex is reported as Invalid pattern, with a code", e.io.err.any { it.contains("[E_USAGE] Invalid pattern") && !it.contains("Internal") }, e.io.all())
    }

    // ───────────── 1.2 internal faults are labelled internal, never "Syntax error" ─────────────
    run {
        val boom = CommandSpec("boom", summary = "t", usage = "boom", execute = { java.util.regex.Pattern.compile("a{"); CommandResult.Success() })
        val npe = CommandSpec("npe", summary = "t", usage = "npe", execute = { throw IllegalStateException("oops") })
        val e = Env(extra = listOf(boom, npe))
        val r = e.run("create ok1\nboom\ncreate ok2")
        check("internal regex fault: E_INTERNAL", e.io.err.any { it.contains("[E_INTERNAL]") && it.contains("this is a bug, not your script") }, e.io.all())
        check("internal fault is not called a syntax error", e.io.err.none { it.contains("Syntax error") }, e.io.all())
        check("internal fault stops the script", !e.file("ok2").exists() && r.status == 1)
        e.io.reset(); e.run("npe")
        check("other internal faults too", e.io.err.any { it.contains("[E_INTERNAL]") && it.contains("IllegalStateException") }, e.io.all())
    }

    // ───────────── 1.3 history ─────────────
    run {
        val hist = listOf("a", "b", "c")
        val e = Env(history = { hist })
        e.run("history"); check("bare history works", e.io.err.isEmpty() && e.io.out.size == 3, e.io.all()); e.io.reset()
        e.run("history 2"); check("history n", e.io.out.size == 2, e.io.all()); e.io.reset()
        e.run("history 0"); check("history 0 is a usage error", e.io.err.any { it.contains("[E_USAGE]") }, e.io.all())
        val e2 = Env(); e2.run("history"); check("empty history says so", e2.io.out == listOf("(no commands recorded yet)"), e2.io.all())
    }

    // ───────────── 2.x quoting matrix (exact bytes) ─────────────
    run {
        val e = Env()
        e.ses.vars["FOO"] = "bar"
        fun write(cmd: String): String { e.file("q.txt").delete(); e.io.reset(); e.run("update /home/q.txt --content $cmd"); return if (e.file("q.txt").exists()) e.text("q.txt") else "<missing: ${e.io.all()}>" }
        check("Q1 single quotes keep C:\\new\\table.txt byte-exact", write("'C:\\new\\table.txt'") == "C:\\new\\table.txt")
        check("Q2 double quotes: \\n is a line break", write("\"a\\nb\"") == "a\nb")
        check("Q3 single quotes: \\\\ stays two backslashes", write("'\\\\'") == "\\\\")
        check("Q4 single quotes do not expand", write("'x \$FOO'") == "x \$FOO")
        check("Q5 double quotes expand", write("\"x \$FOO\"") == "x bar")
        check("Q6 \\\$ in double quotes is a dollar", write("\"x \\\$FOO\"") == "x \$FOO")
        check("Q7 \\\$ in single quotes stays backslash-dollar", write("'x \\\$FOO'") == "x \\\$FOO")
        check("Q8 escaped quote in single quotes", write("'it\\'s'") == "it's")
        check("Q9 POSIX idiom 'it'\\''s' works", write("'it'\\''s'") == "it's")
        check("Q10 regex-ish text is untouched", write("'\\d+\\.\\w'") == "\\d+\\.\\w")
        check("Q11 \\n in single quotes is two characters", write("'a\\nb'") == "a\\nb")
        check("Q12 double quotes: \\\\ is one backslash", write("\"a\\\\b\"") == "a\\b")
        check("Q13 \$ before a non-variable keeps the backslash (regex friendly)", write("\"price\\\$\"") == "price\\\$")
        check("Q14 double-quoted trailing backslash", write("\"C:\\\\\"") == "C:\\")
    }

    // ───────────── 2.2 heredoc ─────────────
    run {
        val e = Env()
        e.ses.vars["FOO"] = "bar"
        e.run("create /home/h1.json <<'EOF'\n{\"p\": \"C:\\new\\t.txt\", \"v\": \"\$FOO\", \"q\": 'x'}\nline2\nEOF\ncreate /home/after")
        check("H1 heredoc is byte-exact and ends with a line break", e.text("h1.json") == "{\"p\": \"C:\\new\\t.txt\", \"v\": \"\$FOO\", \"q\": 'x'}\nline2\n", e.io.all())
        check("H2 the command after the heredoc ran", e.file("after").exists())
        e.io.reset(); e.run("update /home/h1.json --append <<EOF\nmore \\n\nEOF")
        check("H3 append with an unquoted delimiter is also literal", e.text("h1.json").endsWith("line2\nmore \\n\n"), e.text("h1.json"))
        e.io.reset(); e.run("create /home/h2.txt <<-X\n\t\tindented\n\tX")
        check("H4 <<- strips leading tabs", e.text("h2.txt") == "indented\n", e.text("h2.txt"))
        e.io.reset(); e.run("create /home/h3.txt <<EOF\nEOF")
        check("H5 empty heredoc makes an empty file", e.file("h3.txt").exists() && e.text("h3.txt") == "")
        e.io.reset(); val r = e.run("create /home/h4.txt <<EOF\nnever closed")
        check("H6 unterminated heredoc is rejected up front", r.status == 2 && e.io.err.any { it.contains("never terminated") } && !e.file("h4.txt").exists(), e.io.all())
        e.io.reset(); e.run("echo hi <<EOF\nx\nEOF")
        check("H7 heredoc on a command without a body is explained", e.io.err.any { it.contains("can only feed create and update") }, e.io.all())
        e.io.reset(); e.run("create /home/h5 <<EOF\nx\nEOF\ndelete /home/missing")
        check("H8 line numbers after a heredoc stay physical (error is on line 4)", e.io.err.any { it.startsWith("line 4:") }, e.io.all())
        e.io.reset(); e.run("create /home/h6 --content a <<EOF\nx\nEOF")
        check("H9 heredoc plus --content is refused", e.io.err.any { it.contains("not both") }, e.io.all())
        e.io.reset(); e.run("create /home/h7 <<EOF; create /home/h8\nx\nEOF")
        check("H10 heredoc must be last on its line", e.io.err.any { it.contains("last thing on its line") }, e.io.all())
    }

    // ───────────── 2.3 / 2.4 unterminated quote shows the line and a caret ─────────────
    run {
        val e = Env()
        val r = e.run("create a\ncreate b\nupdate /home/a.txt 'it is\ncreate c")
        check("U1 unterminated quote rejects the whole paste atomically", !e.file("a").exists() && r.status == 2)
        check("U2 header names the line", e.io.err[0] == "[E_USAGE] Syntax error (line 3): unterminated quote", e.io.all())
        check("U3 offending line is quoted", e.io.err[1] == "  3 | update /home/a.txt 'it is", e.io.all())
        check("U4 caret is under the opening quote", e.io.err[2] == "    | " + " ".repeat(19) + "^ opened here, never closed", e.io.all())
    }

    // ───────────── 2.5 shell syntax gets its own diagnosis ─────────────
    run {
        val e = Env()
        for ((cmd, needle) in listOf("\$ echo hi" to "shell prompt", "zipcli\$ ls" to "shell prompt", "create a | create b" to "no pipes")) {
            e.io.reset(); e.run(cmd)
            check("S: '$cmd' is not 'Unknown command'", e.io.err.size == 1 && e.io.err[0].contains(needle) && !e.io.err[0].contains("Unknown command"), e.io.all())
            check("S: '$cmd' names the missing features", e.io.err[0].contains("no redirection") && e.io.err[0].contains("no arithmetic"), e.io.all())
        }
    }

    // ───────────── 3.1 confirmation stops the batch before the prompt ─────────────
    run {
        val e = Env(promptable = false)
        e.run("create /home/x1; create /home/x2")
        e.io.reset()
        val r = e.run("create /home/p1\ncreate /home/p2\ndelete /home/x1\ncreate /home/p3\ncreate /home/p4\ncreate /home/p5")
        check("C1 batch stops at the prompt", e.file("p2").exists() && !e.file("p3").exists() && e.file("x1").exists(), e.io.all())
        check("C2 structured pending result", r.pending?.line == 3 && r.pending?.command == "delete /home/x1" && r.status == 4 && r.notRun == 3, r.toString())
        check("C3 footer says what did not run", e.io.err.any { it.startsWith("stopped at line 3: 1 command needs confirmation, 3 command(s) not run") }, e.io.all())
        check("C4 the error line is coded", e.io.err.any { it.contains("[E_NEEDS_CONFIRM]") }, e.io.all())
        e.io.reset(); e.run("set +e\ndelete /home/x1\ncreate /home/after1")
        check("C5 stops even with set +e (later steps may depend on it)", !e.file("after1").exists())
        e.io.reset(); e.run("set -e\ncreate /home/pp1\ndelete /home/x1 --yes\ncreate /home/pp2")
        check("C6 --yes authorises one command", !e.file("x1").exists() && e.file("pp2").exists() && e.io.err.isEmpty(), e.io.all())
        e.io.reset(); e.run("create /home/y1; delete /home/y1 -y; create /home/y2")
        check("C7 -y works too", !e.file("y1").exists() && e.file("y2").exists(), e.io.all())
        e.io.reset(); e.run("create /home/z1\nset -y\ndelete /home/z1\ncreate /home/z2")
        check("C8 set -y authorises the whole session", !e.file("z1").exists() && e.file("z2").exists(), e.io.all())
        e.run("set +y"); e.io.reset(); e.run("create /home/z3\ndelete /home/z3")
        check("C9 set +y turns it off again", e.file("z3").exists())
        val ui = Env(promptable = true); ui.io.answer = false; ui.run("create /home/k"); ui.io.reset(); ui.run("delete /home/k")
        check("C10 interactive terminal still asks and honours 'no'", ui.io.confirms == 1 && ui.file("k").exists() && ui.io.info.contains("Cancelled."), ui.io.all())
    }

    // ───────────── 4.3 / 4.4 exit statuses and error codes ─────────────
    run {
        val e = Env()
        check("X1 not found → status 3 and E_NOT_FOUND", e.run("cat nope.txt").status == 3 && e.io.err[0] == "[E_NOT_FOUND] Not found: /home/nope.txt ( /home is empty).".replace("( /home", "(/home"), e.io.all())
        e.io.reset(); check("X2 unknown command → status 2", e.run("lsit").status == 2 && e.io.err[0].startsWith("[E_USAGE] Unknown command: 'lsit'"), e.io.all())
        e.io.reset(); e.run("create d/"); check("X3 directory error is coded", e.run("cat d").let { e.io.err.any { l -> l.contains("[E_IS_DIR]") } }, e.io.all())
        e.io.reset(); e.run("update f.txt -c x"); val r = e.run("replace f.txt zzz y --fail-on-no-match")
        check("X4 replace --fail-on-no-match → 1", r.status == 1, e.io.all())
        e.io.reset(); check("X5 replace without the flag → 0", e.run("replace f.txt zzz y").status == 0)
        e.io.reset(); e.run("create a1\nlist -z"); check("X6 line prefix + code", e.io.err.any { it.startsWith("line 2: [E_USAGE] Unknown flag: -z for 'list'") }, e.io.all())
        check("X7 unknown flag lists the real flags", e.io.err.any { it.contains("-l/--long") && it.contains("-a/--all") }, e.io.all())
        e.io.reset(); e.run("search x . -r 6"); check("X9 yes/no flag followed by a value names the flag", e.io.err.any { it.contains("-r takes no value") && it.contains("'6'") }, e.io.all())
    }

    // ───────────── 5.1 not-found hints ─────────────
    run {
        val e = Env()
        e.run("create q1.txt a.zip")
        e.io.reset(); e.run("cat zzz.txt")
        check("N1 lists what the folder contains", e.io.err.any { it.contains("/home contains: a.zip, q1.txt") }, e.io.all())
        e.io.reset(); e.run("cat q2.txt")
        check("N2 close names still get did-you-mean", e.io.err.any { it.contains("Did you mean 'q1.txt'") }, e.io.all())
    }

    // ───────────── 5.2 check ─────────────
    run {
        val e = Env()
        e.run("update /home/bad.zcli <<'EOF'\ncreate ok\nlsit /home\nfind x -z\n\ncreate a b --nope\nfor f in *.txt; do\n  nothing here\ndone\ncreate d <<X\nbody\nX\nEOF")
        e.io.reset(); val r = e.run("check bad.zcli")
        check("K1 check reports every problem", e.io.out.count { it.startsWith("line ") } == 4, e.io.all())
        check("K2 with physical line numbers", e.io.out.any { it.startsWith("line 2:") } && e.io.out.any { it.startsWith("line 3:") } && e.io.out.any { it.startsWith("line 5:") } && e.io.out.any { it.startsWith("line 7:") }, e.io.all())
        check("K3 non-zero status and nothing ran", r.status == 2 && !e.file("ok").exists() && !e.file("d").exists(), e.io.all())
        e.run("update /home/good.zcli <<'EOF'\ncreate x\ncreate y <<'T'\nbody\nT\nEOF")
        e.io.reset(); val g = e.run("check good.zcli")
        check("K4 a clean script passes", g.status == 0 && e.io.out.any { it.startsWith("check: OK, 2 command(s)") } && !e.file("x").exists(), e.io.all())
        e.run("update /home/q.zcli <<'EOF'\ncreate a\nupdate f 'oops\nEOF")
        e.io.reset(); val q = e.run("check q.zcli")
        check("K5 check shows quoting problems with a caret", q.status == 2 && e.io.out.any { it.contains("unterminated quote") } && e.io.out.any { it.contains("^ opened here") }, e.io.all())
    }

    // ───────────── 4.1 / 4.2 / 7.4 JSON, count-only, paging ─────────────
    run {
        val e = Env()
        e.run("update a.txt <<'EOF'\nfoo 1\nfoo 2\nfoo 3\nbar\nEOF")
        e.io.reset(); e.run("find foo a.txt --json")
        val j = e.io.out.single()
        check("J1 find --json", j.startsWith("{\"matches\":[{\"path\":\"/home/a.txt\",\"line\":1,\"text\":\"foo 1\"") && j.contains("\"count\":3") && j.contains("\"truncated\":false"), j)
        e.io.reset(); e.run("find foo a.txt --count-only"); check("J2 count-only", e.io.out.single().startsWith("3 match(es) in 1 file(s)"), e.io.all())
        e.io.reset(); e.run("find foo a.txt --max 2"); check("J3 truncation names the way to continue", e.io.out.any { it.contains("--offset 2 to continue") }, e.io.all())
        e.io.reset(); e.run("find foo a.txt --max 2 --offset 2"); check("J4 --offset resumes", e.io.out.any { it.contains("a.txt:3: foo 3") } && e.io.out.none { it.contains("foo 1") }, e.io.all())
        e.io.reset(); e.run("find nothing a.txt --json"); check("J5 no match is still JSON with status 1", e.io.out.single().contains("\"count\":0"), e.io.all())
        e.io.reset(); e.run("search a --json"); check("J6 search --json", e.io.out.single().contains("\"path\":\"/home/a.txt\"") && e.io.out.single().contains("\"type\":\"file\""), e.io.all())
        e.io.reset(); e.run("list --json"); check("J7 list --json", e.io.out.single().startsWith("[{\"path\":\"/home\",\"entries\":[{\"name\":\"a.txt\""), e.io.all())
        e.io.reset(); e.run("cat a.txt --json --lines 2"); check("J8 cat --json pages", e.io.out.single().contains("\"lines\":[\"foo 1\",\"foo 2\"]") && e.io.out.single().contains("\"next_offset\":2"), e.io.all())
        e.io.reset(); e.run("cat a.txt --lines 2"); check("J9 cat truncation hint", e.io.out.last().contains("--offset 2"), e.io.all())
        e.io.reset(); e.run("cat a.txt --offset 3"); check("J10 cat --offset", e.io.out.first() == "bar", e.io.all())
        e.io.reset(); e.run("replace a.txt foo baz --json --dry-run"); check("J11 replace --json", e.io.out.single().contains("\"replaced\":3") && e.io.out.single().contains("\"dry_run\":true"), e.io.all())
        e.io.reset(); e.run("help find --json"); check("J12 help --json schema", e.io.out.single().contains("\"name\":\"find\"") && e.io.out.single().contains("\"long\":\"offset\""), e.io.all())
        e.io.reset(); e.run("search zz -r"); e.io.reset()
        e.run("create s1.txt s2.txt s3.txt"); e.io.reset(); e.run("search s --max 2"); check("J13 search cap hint", e.io.out.last().contains("--offset 2"), e.io.all())
    }

    // ───────────── 6.1 / 5.3 info and app-only commands ─────────────
    run {
        val e = Env(transport = "mcp")
        e.io.reset(); e.run("saf list")
        check("M1 saf over MCP says why", e.io.err.single().contains("not available in this session") && e.io.err.single().contains("policy"), e.io.all())
        e.io.reset(); e.run("mcp status"); check("M2 mcp too", e.io.err.single().contains("not available in this session"), e.io.all())
        e.io.reset(); e.run("info"); check("M3 info names the transport and the missing commands", e.io.out[0].contains("transport: mcp") && e.io.out.any { it.startsWith("not available in this session: saf, mcp") }, e.io.all())
        e.io.reset(); e.run("info --json"); check("M4 info --json", e.io.out.single().contains("\"transport\":\"mcp\"") && e.io.out.single().contains("\"mcp_output_chars\":100000") && e.io.out.single().contains("\"errexit\":true"), e.io.all())
    }

    // ───────────── 6.4 / 7.1 state and wildcards ─────────────
    run {
        val e = Env()
        e.run("set A=1; set B=2; set +e"); e.run("set --reset"); e.io.reset(); e.run("set")
        check("R1 set --reset clears variables and restores options", e.io.out.contains("(no variables)") && e.io.out[0].startsWith("errexit=on"), e.io.all())
        e.run("create n1.md n2.md t.txt"); e.io.reset(); e.run("list /home/*.md")
        check("W1 list expands wildcards", e.io.err.isEmpty() && e.io.out == listOf("n1.md", "n2.md") && e.io.out.none { it.contains("t.txt") }, e.io.all())
        e.io.reset(); e.run("find x /home/*.md"); check("W2 find explains that it does not expand", e.io.err.any { it.contains("does not expand wildcards") && it.contains("--name") }, e.io.all())
        e.io.reset(); e.run("search x /home/*.md"); check("W3 search explains too", e.io.err.any { it.contains("does not expand wildcards") }, e.io.all())
    }

    // ───────────── 6.3 per-command report ─────────────
    run {
        val e = Env()
        val r = e.run("create a\nfind zz a\ncreate b")
        check("P1 stopped at line 2 and reports per command", r.reports.size == 2 && r.reports[0].status == 0 && r.reports[1].line == 2 && r.reports[1].status == 1, r.reports.toString())
        val r2 = e.run("cat nope")
        check("P2 report carries the message", r2.reports.single().message?.contains("[E_NOT_FOUND]") == true, r2.reports.toString())
    }

    println("\nPASS=$pass FAIL=$fail")
    if (fail > 0) System.exit(1)
}
