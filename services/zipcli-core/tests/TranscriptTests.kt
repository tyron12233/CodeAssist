// Golden transcripts: every tests/transcripts/NAME.zcli is run in a fresh temporary root (non-interactive, like MCP)
// and its output is compared character for character with NAME.expected.
// Run:  java TranscriptTestsKt <dir>          compare
//       java TranscriptTestsKt <dir> --update regenerate the .expected files (review the diff!)
import com.zipcli.ke.domain.*
import com.zipcli.ke.domain.commands.*
import com.zipcli.ke.domain.script.*
import com.zipcli.ke.fs.*
import com.zipcli.ke.zip.*
import kotlinx.coroutines.runBlocking
import java.io.File

class LogIo : ScriptIo {
    val log = ArrayList<String>()
    override val canPrompt: Boolean get() = false
    override fun print(lines: List<String>, kind: LineKind) {
        for (l in lines) log += when (kind) { LineKind.ERROR -> "error: $l"; LineKind.INFO -> "info: $l"; LineKind.TRACE -> "trace: $l"; else -> l }
    }
    override suspend fun confirm(prompt: String) = false
    override fun clear() {}
    override fun openEditor(path: String, content: String) {}
    override suspend fun requestSafPermission() {}
    override fun progress(message: String?) {}
    override fun cwdChanged(cwd: String) {}
}

fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "tests/transcripts" })
    val update = "--update" in args
    var pass = 0; var fail = 0
    for (script in dir.listFiles { f -> f.name.endsWith(".zcli") }!!.sortedBy { it.name }) {
        val root = File.createTempFile("zcli", "").also { it.delete(); it.mkdirs() }
        val fs = NioFileSystem(mapOf("/home" to root))
        lateinit var reg: CommandRegistry
        reg = CommandRegistry(buildCoreCommands { reg } + buildContentCommands() + buildArchiveCommands(Zipper(fs), Unzipper(fs)) + buildScriptCommands())
        val io = LogIo()
        val runner = ScriptRunner(reg, CommandParser(reg), fs, io, "mcp")
        val result = runBlocking { runner.run(script.readText(), ShellSession("/home"), interactive = false) }
        val actual = (io.log + "[exit ${result.status}]").joinToString("\n") { it.replace(Regex("\\(\\d+\\.\\ds\\)"), "(Ns)") } + "\n"
        val exp = File(dir, script.name.removeSuffix(".zcli") + ".expected")
        if (update) { exp.writeText(actual); println("wrote ${exp.name}"); continue }
        if (exp.exists() && exp.readText() == actual) pass++
        else { fail++; println("FAIL: ${script.name}\n--- expected\n${if (exp.exists()) exp.readText() else "<missing>"}--- actual\n$actual") }
    }
    if (!update) println("TRANSCRIPTS PASS=$pass FAIL=$fail")
    if (fail > 0) System.exit(1)
}
