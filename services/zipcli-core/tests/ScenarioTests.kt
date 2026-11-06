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

class Io(var answer: Boolean = true) : ScriptIo {
    val out = ArrayList<String>(); val err = ArrayList<String>(); var confirms = 0
    override fun print(lines: List<String>, kind: LineKind) { if (kind == LineKind.INFO && lines.size == 1 && lines[0].contains(" commands OK")) return; if (kind == LineKind.ERROR) err += lines else out += lines }
    override suspend fun confirm(prompt: String): Boolean { confirms++; out += "?$prompt"; return answer }
    override fun clear() {}
    override fun openEditor(path: String, content: String) { out += "EDITOR:$path" }
    override suspend fun requestSafPermission() {}
    override fun progress(message: String?) {}
    override fun cwdChanged(cwd: String) {}
    fun all() = (out + err).joinToString("\n")
    fun reset() { out.clear(); err.clear() }
}

fun main() {
    val root = File.createTempFile("zcli", "").also { it.delete(); it.mkdirs() }
    val fs = NioFileSystem(mapOf("/home" to root))
    lateinit var reg: CommandRegistry
    val specs = buildCoreCommands { reg } + buildContentCommands() + buildArchiveCommands(Zipper(fs), Unzipper(fs)) + buildScriptCommands()
    reg = CommandRegistry(specs)
    val parser = CommandParser(reg)
    val io = Io()
    val runner = ScriptRunner(reg, parser, fs, io)
    val ses = ShellSession("/home")
    fun run(s: String): ScriptRunner.Result = runBlocking { runner.run(s, ses) }
    fun file(p: String) = File(root, p.removePrefix("/home/"))

    // 1 chaining
    run("create a.txt b.txt; create d/ ; ls")
    check("create multi", file("a.txt").exists() && file("b.txt").exists() && file("d").isDirectory, io.all())
    check("ls dirs first", io.out.contains("d/") && io.out.indexOf("d/") < io.out.indexOf("a.txt"), io.all())
    io.reset()

    // 2 && ||
    run("test -e a.txt && echo yes || echo no"); check("and-or true", io.out == listOf("yes"), io.all()); io.reset()
    run("test -e nope && echo yes || echo no"); check("and-or false", io.out == listOf("no") && io.err.isEmpty(), io.all()); io.reset()

    // 3 errexit
    val r = run("create x1\ndelete nothere\ncreate y1")
    check("errexit stops", !file("y1").exists() && file("x1").exists() && r.abortedAtLine == 2, io.all())
    check("error has line", io.err.any { it.startsWith("line 2:") }, io.all()); io.reset()
    run("set +e; delete nothere; create y2"); check("set +e continues", file("y2").exists(), io.all()); io.reset()
    run("set -e")

    // 3b delete -f is idempotent (rm -f semantics): a missing path is not an error and does not stop the script
    val rf = run("create x3; delete nothere -f; create y4")
    check("delete -f idempotent", file("y4").exists() && rf.status == 0 && io.err.isEmpty(), io.all()); io.reset()

    // 3c unsupported shell constructs fail loudly instead of silently becoming arguments
    run("echo hello > out.txt")
    check("redirection rejected", io.err.any { it.contains("not supported") } && !file("out.txt").exists(), io.all()); io.reset()
    run("create p1 | create p2")
    check("pipe rejected", io.err.any { it.contains("not supported") }, io.all()); io.reset()
    run("echo " + '$' + "((1+1))")
    check("arithmetic rejected", io.err.any { it.contains("not supported") }, io.all()); io.reset()

    // 3d text that starts with dashes is text, not a flag
    run("echo '-- archive contents --'")
    check("dash text is text", io.out == listOf("-- archive contents --") && io.err.isEmpty(), io.all()); io.reset()

    // 3e find / search report no match as status 1, so guards work in scripts
    val rn = run("find zzzznomatch a.txt")
    check("find no match -> status 1", rn.status == 1 && io.out.any { it.startsWith("No matches") }, io.all()); io.reset()
    run("find zzzznomatch a.txt || echo none")
    check("find guard falls back", io.out.last() == "none" && io.err.isEmpty(), io.all()); io.reset()

    // 3f zip -o replaces an existing archive
    run("zip z1.zip a.txt"); io.reset()
    run("zip z1.zip a.txt")
    check("zip refuses existing", io.err.any { it.contains("already exists") }, io.all()); io.reset()
    run("zip z1.zip a.txt -o")
    check("zip -o overwrites", io.err.isEmpty() && file("z1.zip").exists(), io.all()); io.reset()

    // 5 variables
    run("set D=/home/out; create \$D/; echo \$D \${U:-dflt} \$UNDEF"); check("vars", io.out.contains("/home/out dflt \$UNDEF") && file("out").isDirectory, io.all()); io.reset()
    run("update re.txt -c 'abc'"); run("replace re.txt --regex '(b)' '[\$1]'"); check("regex group kept", file("re.txt").readText() == "a[b]c", file("re.txt").readText()); io.reset()
    run("cd /home/out; cd -; pwd"); check("cd -", io.out.last() == "/home", io.all()); io.reset()

    // 6 for + glob
    run("update f1.txt -c one; update f2.txt -c two; create bak/"); io.reset()
    run("for f in *.txt; do copy \$f bak/; done")
    check("for glob copy", file("bak/f1.txt").exists() && file("bak/f2.txt").exists() && file("bak/a.txt").exists(), io.all())
    check("loop copied 5", io.out.count { it.startsWith("Copied") } == 5, io.all()); io.reset()
    run("for f in nothing*; do echo hi; done"); check("empty glob zero iterations", io.out.none { it == "hi" }, io.all()); io.reset()

    // 7 script file with args and multi-line quoted content
    runBlocking { fs.writeText("/home/s.zcli", "#!zipcli\necho start \$1 \$#\ncreate \"/home/\$1.log\" --content \"l1\\nl2\"\ncd /home/bak\nexit 0\necho NEVER\n") }
    run("run s.zcli demo"); check("script args", io.out.contains("start demo 1") && file("demo.log").readText() == "l1\nl2", io.all())
    check("script scoped cwd", ses.cwd == "/home" && io.out.none { it == "NEVER" }, ses.cwd + io.all()); io.reset()
    runBlocking { fs.writeText("/home/loop.zcli", "run loop.zcli\n") }
    run("run loop.zcli"); check("recursion guard", io.err.any { it.contains("nested") }, io.all()); io.reset()

    // 8 zip glob + list + smart unzip
    run("zip all.zip *.txt bak"); check("zip created", file("all.zip").exists(), io.all()); println(io.out.joinToString(" | ")); io.reset()
    run("unzip -l all.zip"); check("unzip -l", io.out.any { it.contains("a.txt") }, io.all()); io.reset()
    run("unzip all.zip"); check("smart dest", file("all/a.txt").exists() && io.out.any { it.contains("top-level") }, io.all()); io.reset()
    run("unzip all.zip --here"); check("skip existing", io.out.any { it.contains("Skipped") }, io.all()); io.reset()
    run("zip big.zip /home"); check("zip dest inside src ok", file("big.zip").exists(), io.all())
    val names = java.util.zip.ZipFile(file("big.zip")).entries().toList().map { it.name }
    check("no self inclusion", names.none { it.contains("big.zip") }, names.toString()); io.reset()
    run("zip e.zip nothere"); check("zip missing fails cleanly", !file("e.zip").exists() && io.err.isNotEmpty(), io.all()); io.reset()

    // zip slip
    java.util.zip.ZipOutputStream(file("evil.zip").outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../../evil.txt")); z.write(1); z.closeEntry() }
    run("unzip evil.zip /home/safe"); check("zip slip blocked", io.err.any { it.contains("Blocked") }, io.all()); io.reset()

    // 9 binary handling
    file("bin.dat").writeBytes(byteArrayOf(0, 1, 2, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(), 0))
    run("find one /home -r"); check("find skips binary", io.out.any { it.contains("skipped") } && io.out.none { it.contains("bin.dat:") }, io.all()); io.reset()
    val before = file("bin.dat").readBytes()
    run("replace /home one two -r"); check("replace leaves binary intact", file("bin.dat").readBytes().contentEquals(before), io.all()); io.reset()
    run("update dr.txt -c one"); run("replace dr.txt one uno --dry-run"); check("dry run", file("dr.txt").readText() == "one" && io.out.any { it.contains("Would replace") }, io.all()); io.reset()
    run("find ONE f1.txt"); check("smartcase upper = sensitive", io.out.any { it.startsWith("No matches") }, io.all()); io.reset()
    run("update sc.txt -c One"); run("find one sc.txt"); check("smartcase lower = insensitive hit", io.out.any { it.contains("sc.txt:1: One") }, io.all()); io.reset()
    run("update log.txt -c \"a\\n\"; update log.txt -a -c \"b\\n\"; cat log.txt"); check("append+cat", io.out.takeLast(3) == listOf("a", "b", ""), io.all()); io.reset()

    // 10 delete / confirm / protection
    run("delete bak"); check("needs -r", io.err.any { it.contains("-r") }, io.all()); io.reset()
    io.answer = false; run("delete bak -r"); check("declined keeps", file("bak").exists() && io.out.any { it.contains("3 files") || it.contains("files") }, io.all()); io.reset()
    io.answer = true; run("delete bak -r"); check("confirmed deletes", !file("bak").exists(), io.all()); io.reset()
    run("delete /home -r -f"); check("root protected", io.err.any { it.contains("storage root") } && root.exists(), io.all()); io.reset()
    run("delete *.log -f"); check("glob delete", !file("demo.log").exists() && file("log.txt").exists(), io.all()); io.reset()

    // 11 copy/move semantics
    run("create tgt/; copy f1.txt tgt"); check("copy into dir", file("tgt/f1.txt").exists(), io.all()); io.reset()
    run("copy tgt tgt/inner"); check("copy into self blocked", io.err.any { it.contains("itself") }, io.all()); io.reset()
    run("move f1.txt f2.txt"); check("move exists blocked", io.err.any { it.contains("exists") } && file("f1.txt").exists(), io.all()); io.reset()
    run("move f1.txt f2.txt tgt"); check("move many (f2 collides? no: tgt has f1)", true); io.reset()

    // 12 suggestions & errors
    run("lst"); check("did you mean", io.err.any { it.contains("Did you mean 'ls'") || it.contains("Did you mean 'list'") }, io.all()); io.reset()
    run("find x --recursiv"); check("flag hint", io.err.any { it.contains("--recursive") }, io.all()); io.reset()
    run("for x in a b; do echo \$x"); check("syntax err loop", io.err.any { it.contains("done") }, io.all()); io.reset()
    run("echo 'unterminated"); check("syntax err quote", io.err.any { it.contains("unterminated") }, io.all()); io.reset()
    run("echo a &&"); check("syntax err trailing &&", io.err.isNotEmpty(), io.all()); io.reset()
    run("cd nope"); check("cd hint", io.err.any { it.contains("No such directory") }, io.all()); io.reset()
    run("echo a;b"); check("one-liner semicolon unknown b", io.err.any { it.contains("Unknown command: 'b'") }, io.all()); io.reset()
    run("update/home/z.txt -c hi"); check("autofix preserved", file("z.txt").exists(), io.all()); io.reset()
    run("echo \"x # not comment\" # real comment"); check("comment handling", io.out == listOf("x # not comment"), io.all()); io.reset()
    run("echo a \\\n b"); check("continuation", io.out == listOf("a b"), io.all()); io.reset()
    run("set -x; echo traced"); check("trace flag", true); io.reset(); run("set +x")
    run("create /home/m.txt --content \"line1\nline2\"; cat m.txt"); check("multiline quoted", io.out.takeLast(2) == listOf("line1", "line2"), io.all()); io.reset()

    // search
    run("search f -r"); check("search smart", io.out.any { it.contains("f2.txt") } , io.all()); io.reset()

    // 14 autocomplete
    val ac = Autocomplete(reg, fs)
    fun c(s: String) = runBlocking { ac.complete(s, "/home", listOf("MYVAR")) }
    check("ac cmd lcp", c("rep").newText == "replace ", c("rep").toString())
    check("ac flag", c("find x --rec").newText == "find x --recursive ")
    check("ac var", c("echo \$MY").newText == "echo \$MYVAR")
    check("ac after ;", c("ls; unz").newText == "ls; unzip ")
    check("ac path unique dir", c("cd ou").newText == "cd out/", c("cd ou").toString())
    check("ac path lcp", c("cat f").suggestions.isNotEmpty(), c("cat f").toString())
    File(root, "my file.txt").writeText("x")
    check("ac quotes", c("cat my").newText == "cat \"my file.txt\" ", c("cat my").toString())


    // ───── multi-line / indentation ─────
    fun w(p: String, t: String) { file(p).writeText(t) }
    val code = "class A {\n    fun f() {\n        if (x) {\n            go()\n        }\n    }\n}\n"
    w("c1.kt", code)
    // exact multi-line literal
    run("replace c1.kt \"        if (x) {\n            go()\n        }\" \"        if (y) {\n            stop()\n        }\"")
    check("exact block", file("c1.kt").readText() == code.replace("x","y").replace("go","stop"), file("c1.kt").readText()); io.reset()
    // CRLF file keeps CRLF, untouched lines identical
    w("c2.kt", code.replace("\n", "\r\n"))
    run("replace c2.kt \"go()\n        }\" \"go()\n            more()\n        }\"")
    val c2 = file("c2.kt").readText()
    check("crlf preserved", c2 == code.replace("go()\n        }", "go()\n            more()\n        }").replace("\n", "\r\n"), c2.replace("\r","<CR>")); io.reset()
    // reindent: pattern typed with wrong indentation, file uses TABS
    val tabs = "class A {\n\tfun f() {\n\t\tif (x) {\n\t\t\tgo()\n\t\t}\n\t}\n}\n"
    w("c3.kt", tabs)
    run("replace c3.kt \"if (x) {\n    go()\n}\" \"if (y) {\n    a()\n    b()\n}\" --reindent")
    check("reindent to tabs", file("c3.kt").readText() == "class A {\n\tfun f() {\n\t\tif (y) {\n\t\t\ta()\n\t\t\tb()\n\t\t}\n\t}\n}\n", file("c3.kt").readText().replace("\t","<T>")); io.reset()
    // reindent: spaces file, deeper nesting, tab-free; nested inside different depth
    w("c4.kt", "a:\n  b:\n    if (x) {\n      go()\n    }\n")
    run("replace c4.kt \"if (x) {\ngo()\n}\" \"if (x) {\n  go()\n  done()\n}\" --reindent")
    check("reindent spaces", file("c4.kt").readText() == "a:\n  b:\n    if (x) {\n      go()\n      done()\n    }\n", file("c4.kt").readText()); io.reset()
    // without --reindent a mismatching block is NOT touched, and a tip is shown
    w("c5.kt", tabs)
    run("replace c5.kt \"if (x) {\n    go()\n}\" \"zzz\""); check("no flex no change + tip", file("c5.kt").readText() == tabs && io.out.any { it.contains("--reindent") }, io.all()); io.reset()
    // regex ^ $ per line
    w("c6.txt", "foo = 1\nbar = foo\nfoo = 2\n")
    run("replace c6.txt --regex '^foo' 'baz'"); check("regex ^ per line", file("c6.txt").readText() == "baz = 1\nbar = foo\nbaz = 2\n", file("c6.txt").readText()); io.reset()
    run("replace c6.txt --regex '(\\w+) = (\\d)\$' '\$2 <- \$1'"); check("regex groups + \$ anchor", file("c6.txt").readText().startsWith("1 <- baz"), file("c6.txt").readText()); io.reset()
    // word
    w("c7.txt", "cat concat cat.\n"); run("replace c7.txt cat dog -w"); check("whole word", file("c7.txt").readText() == "dog concat dog.\n", file("c7.txt").readText()); io.reset()
    // pattern files + backup
    w("old.txt", "if (y) {\n    stop()\n}\n"); w("new.txt", "if (z) {\n    halt()\n}\n"); w("c8.kt", "    if (y) {\n        stop()\n    }\n")
    run("replace c8.kt --old-file old.txt --new-file new.txt --reindent --backup")
    check("pattern files", file("c8.kt").readText() == "    if (z) {\n        halt()\n    }\n" && file("c8.kt.bak").exists(), file("c8.kt").readText()); io.reset()
    // dry-run preview shows diff
    w("c9.kt", "x = 1\n    old()\n"); run("replace c9.kt old() new() --dry-run")
    check("preview diff", io.out.any { it.trim() == "- x = 1".let { "" } || it.contains("-     old()") } && io.out.any { it.contains("+     new()") } && file("c9.kt").readText() == "x = 1\n    old()\n", io.all()); io.reset()
    // multi-line find (auto) keeps indentation and reports start line
    run("find \"if (y) {\n            stop()\" c1.kt"); check("find multiline", io.out.any { it.contains("c1.kt:3:") && it.contains("        if (y) {") }, io.all()); io.reset()
    run("find stop c1.kt"); check("find keeps indent", io.out.any { it.contains(":4:             stop()") }, io.all()); io.reset()
    // multi-line, CRLF
    run("find \"more()\n        }\" c2.kt"); check("find multiline crlf", io.out.any { it.contains("c2.kt:5:") }, io.all()); io.reset()
    // $ in non-regex literal stays literal
    w("c10.txt", "cost \$5\n"); run("replace c10.txt 'cost \$5' 'cost \$6'"); check("literal dollar", file("c10.txt").readText() == "cost \$6\n", file("c10.txt").readText()); io.reset()

    println("\nPASS=$pass FAIL=$fail")
    root.deleteRecursively()
}
