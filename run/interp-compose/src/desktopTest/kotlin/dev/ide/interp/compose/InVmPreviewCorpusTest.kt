package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import dev.ide.vm.ClassPath
import dev.ide.vm.ReflectiveNativeBindings
import dev.ide.vm.Vm
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * A corpus of source previews, each rendered both directly and inside `:jvm-vm` (as iOS renders it): the
 * two frames, and the problems reported, must be the same. A preview the VM cannot run fails here with the
 * VM's reason, which is how the JDK floor finds its gaps.
 */
class InVmPreviewCorpusTest {

    private val imports = """
        import androidx.compose.animation.core.animateFloatAsState
        import androidx.compose.foundation.Canvas
        import androidx.compose.foundation.background
        import androidx.compose.foundation.border
        import androidx.compose.foundation.clickable
        import androidx.compose.foundation.layout.*
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.foundation.rememberScrollState
        import androidx.compose.foundation.shape.CircleShape
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.foundation.verticalScroll
        import androidx.compose.material3.*
        import androidx.compose.runtime.*
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.draw.clip
        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.text.style.TextAlign
        import androidx.compose.ui.unit.dp
        import androidx.compose.ui.unit.sp
    """.trimIndent()

    /** name to source body; each declares `@Composable fun P()`. */
    private val corpus: List<Pair<String, String>> = listOf(
        "controls" to """
            @Composable fun P() {
                MaterialTheme {
                    Surface {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            var checked by remember { mutableStateOf(true) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { checked = it })
                                Text("Checkbox")
                            }
                            Switch(checked = true, onCheckedChange = {})
                            Slider(value = 0.4f, onValueChange = {})
                            LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.fillMaxWidth())
                            RadioButton(selected = true, onClick = {})
                        }
                    }
                }
            }
        """,
        "dataAndLazy" to """
            data class Item(val title: String, val price: Int)
            val items = listOf(Item("Coffee", 3), Item("Tea", 2), Item("Cake", 5))
            @Composable fun Row(item: Item) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.title, fontWeight = FontWeight.Bold)
                    Text("$" + item.price)
                }
            }
            @Composable fun P() {
                MaterialTheme {
                    LazyColumn {
                        items(items) { Row(it) }
                        item { HorizontalDivider() }
                        item { Text("Total: " + items.sumOf { it.price }, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        """,
        "canvasAndShapes" to """
            @Composable fun P() {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF6200EE), Color(0xFF03DAC5))))) {
                    Canvas(Modifier.size(120.dp).align(Alignment.Center)) {
                        drawCircle(Color.White, radius = size.minDimension / 3)
                        drawLine(Color.Black, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 6f)
                    }
                    Box(Modifier.padding(16.dp).size(40.dp).clip(CircleShape).background(Color.Yellow).border(2.dp, Color.Red, CircleShape))
                }
            }
        """,
        "scaffold" to """
            @OptIn(ExperimentalMaterial3Api::class)
            @Composable fun P() {
                MaterialTheme {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("Inbox") }) },
                        floatingActionButton = { FloatingActionButton(onClick = {}) { Text("+") } },
                    ) { inner ->
                        Column(Modifier.padding(inner).padding(16.dp)) {
                            Card(Modifier.fillMaxWidth()) { Text("A card", Modifier.padding(16.dp)) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {}) { Text("Outlined") }
                        }
                    }
                }
            }
        """,
        "stateAndLogic" to """
            sealed interface Status { object Loading : Status; data class Done(val n: Int) : Status }
            fun label(s: Status): String = when (s) { Status.Loading -> "Loading"; is Status.Done -> "Done " + s.n }
            @Composable fun P() {
                var count by remember { mutableIntStateOf(3) }
                val statuses = remember { listOf(Status.Loading, Status.Done(count)) }
                val alpha by animateFloatAsState(1f, label = "a")
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                            for (s in statuses) Text(label(s), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                            Button(onClick = { count++ }, shape = RoundedCornerShape(4.dp)) { Text("Count " + count) }
                            if (count > 2) AssistChip(onClick = {}, label = { Text("chip") })
                        }
                    }
                }
            }
        """,
        "textField" to """
            @Composable fun P() {
                MaterialTheme {
                    Column(Modifier.padding(16.dp)) {
                        var text by remember { mutableStateOf("hello") }
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") })
                        TextField(value = "world", onValueChange = {})
                    }
                }
            }
        """,
    )

    private fun blob(name: String, body: String): ByteArray {
        val code = "package demo\n$imports\n${body.trimIndent()}"
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program["P/0"] ?: error("$name: no P/0; have ${program.keys}")
        val diagnostics = program.values.flatMap { it.diagnostics }.map { it.reason }
        if (diagnostics.isNotEmpty()) println("IN-VM-CORPUS $name lowering diagnostics: $diagnostics")
        return VmPreviewEntry.encode(entry, program, emptyList())
    }

    private fun vm() = Vm(
        ClassPath(System.getProperty("java.class.path").split(File.pathSeparator).filterNot { "kotlin-reflect" in File(it).name }),
        bindings = ReflectiveNativeBindings(
            loader = javaClass.classLoader,
            prepare = {
                val library = Class.forName("org.jetbrains.skiko.Library")
                library.getMethod("load").invoke(library.getField("INSTANCE").get(null))
            },
        ),
        systemProperties = listOf("os.name", "os.arch", "java.vendor", "java.version").associateWith { System.getProperty(it) },
    )

    @Test
    fun everyPreviewRendersTheSameInsideTheVm() {
        val vm = vm()
        val failures = ArrayList<String>()
        val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
        for ((name, body) in corpus) {
            val blob = blob(name, body)
            val direct = VmPreviewEntry.renderChecked(blob, 360, 480, 2f)
            val start = System.nanoTime()
            val interpreted = try {
                vm.invokeStatic("dev/ide/interp/compose/VmPreviewEntry", "renderChecked", "([BIIF)Ljava/lang/String;", blob, 360, 480, 2f) as String
            } catch (e: Throwable) {
                failures.add("$name: VM threw ${e::class.simpleName}: ${e.message?.take(1500)}")
                continue
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            runCatching {
                val png = vm.invokeStatic("dev/ide/interp/compose/VmPreviewEntry", "renderPng", "([BIIF)[B", blob, 360, 480, 2f) as ByteArray
                File(out, "in-vm-corpus-$name.png").writeBytes(png)
            }
            println("IN-VM-CORPUS $name: ${if (direct == interpreted) "SAME" else "DIFFERENT"} in ${ms}ms; direct problems=${direct.lines().drop(1)}")
            if (direct != interpreted) failures.add("$name:\n  direct: $direct\n  vm:     $interpreted")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun theDarkOptionReachesIsSystemInDarkThemeInsideTheVm() {
        val blob = blob("theme", """
            @Composable fun P() {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { Text(if (dark) "Night" else "Day") }
                }
            }
        """)
        val vm = vm()
        fun frame(dark: Boolean): Array<*> {
            val result = vm.invokeStatic(
                "dev/ide/interp/compose/VmPreviewEntry", "renderFrame", "([BIIFFZJ)[Ljava/lang/Object;",
                blob, 200, 120, 2f, 1.5f, dark, 0L,
            ) as dev.ide.vm.VmRefArray
            return result.data
        }
        val day = frame(false)
        val night = frame(true)
        kotlin.test.assertNull(day[1], "no problems by day")
        kotlin.test.assertNull(night[1], "no problems by night")
        val directDay = VmPreviewEntry.renderFrame(blob, 200, 120, 2f, 1.5f, false, 0L)
        kotlin.test.assertTrue((day[0] as ByteArray).contentEquals(directDay[0] as ByteArray), "the VM's day frame is the direct one")
        kotlin.test.assertTrue(!(day[0] as ByteArray).contentEquals(night[0] as ByteArray), "night renders differently")
    }

    @Test
    fun aLiveSessionTakesATapAndShowsTheNewState() {
        val blob = blob("counter", """
            @Composable fun P() {
                var count by remember { mutableIntStateOf(0) }
                MaterialTheme {
                    Box(Modifier.fillMaxSize().clickable { count++ }, contentAlignment = Alignment.Center) {
                        Text("Tapped " + count, fontSize = 28.sp)
                    }
                }
            }
        """)
        val entry = "dev/ide/interp/compose/VmPreviewEntry"
        val vm = vm()
        fun vmFrame(id: Int): Array<*> = (vm.invokeStatic(entry, "frame", "(I)[Ljava/lang/Object;", id) as dev.ide.vm.VmRefArray).data
        fun settle(frame: () -> Array<*>): Array<*> {
            var f = frame()
            var n = 0
            while (f[2] == true && n++ < 120) { vm.runPendingTasks(); Thread.sleep(16); f = frame() }
            return f
        }
        val id = vm.invokeStatic(entry, "open", "([BIIFFZJ)I", blob, 300, 200, 2f, 1f, false, 0L) as Int
        val before = settle { vmFrame(id) }
        vm.invokeStatic(entry, "pointer", "(IIFF)V", id, 0, 150f, 100f)
        vm.invokeStatic(entry, "pointer", "(IIFF)V", id, 2, 150f, 100f)
        val after = settle { vmFrame(id) }
        vm.invokeStatic(entry, "close", "(I)V", id)
        kotlin.test.assertNull(after[1], "no problems")
        kotlin.test.assertTrue(!(before[0] as ByteArray).contentEquals(after[0] as ByteArray), "the tap changed the frame")

        // The same session run directly: the VM's settled frame is the one a direct run settles on.
        val direct = VmPreviewEntry.open(blob, 300, 200, 2f, 1f, false, 0L)
        fun directSettle(): Array<*> {
            var f = VmPreviewEntry.frame(direct)
            var n = 0
            while (f[2] == true && n++ < 120) { Thread.sleep(16); f = VmPreviewEntry.frame(direct) }
            return f
        }
        directSettle()
        VmPreviewEntry.pointer(direct, 0, 150f, 100f)
        VmPreviewEntry.pointer(direct, 2, 150f, 100f)
        val directAfter = directSettle()
        VmPreviewEntry.close(direct)
        File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots"), "in-vm-session-after-tap.png").writeBytes(after[0] as ByteArray)
        kotlin.test.assertTrue((after[0] as ByteArray).contentEquals(directAfter[0] as ByteArray), "the VM's frame after the tap is the direct one")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version = 1L
        override fun length() = text.length
    }

    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
