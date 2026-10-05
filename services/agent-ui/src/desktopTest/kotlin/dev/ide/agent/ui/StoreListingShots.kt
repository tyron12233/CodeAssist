package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.*
import dev.ide.ui.editor.preview.PreviewIssue
import dev.ide.ui.screens.EditorScreen
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.theme.rememberJetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the real IDE screens over a staged sample project, for the store listing. Writes PNGs only. */
class StoreListingShots {
    init { UiPluginHost.register(AgentUiPlugin) }

    @Test
    fun heroEditor() {
        shot("hero-editor.png", SampleProject.NOTE_CARD) { }
    }

    @Test
    fun composePreview() {
        shot("compose-preview.png", SampleProject.NOTE_CARD, host = NotesPreviewHost) {
            active!!.viewMode = EditorViewMode.Split
        }
    }

    @Test
    fun kotlinCompletion() {
        shot("kotlin-completion.png", SampleProject.VIEW_MODEL, typeAt = "notes -> notes." to "fi") { }
    }

    @Test
    fun buildApk() {
        val b = object : ShotBackend() {
            override val buildState: StateFlow<BuildState> = MutableStateFlow(SampleBuild.succeeded())
        }
        shot("build-apk.png", SampleProject.MAIN, backend = b) { consoleOpen = true }
    }

    @Test
    fun agentChat() {
        val b = object : ShotBackend() {
            override val agent: AgentService = SampleAgent.service()
        }
        shot("agent-chat.png", SampleProject.NOTE_CARD, backend = b) { selectedRightPanel = "agent.chat" }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun completionCallout() {
        val scene = ImageComposeScene(width = 720, height = 640, density = Density(2f)) {
            CodeAssistTheme(dark = true, codeFont = rememberJetBrainsMono()) {
                Box(Modifier.fillMaxSize().background(dev.ide.ui.theme.Ide.colors.editorBg)) {
                    dev.ide.ui.editor.CompletionList(SampleCompletion.kotlinListMembers, selectedIndex = 0, prefix = "fi",
                        width = 360.dp, onPick = {}, onHover = {}, docsBeside = false)
                }
            }
        }
        try {
            var img = scene.render()
            for (f in 1..40) { Thread.sleep(20); img = scene.render(f * 16_666_667L) }
            File(OUT_DIR).mkdirs()
            File("$OUT_DIR/callout-completion.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    @Test
    fun tabletPreview() {
        shot("tablet10-preview.png", SampleProject.NOTE_CARD, w = 2560, h = 1600, host = NotesPreviewHost, showTree = true) {
            active!!.viewMode = EditorViewMode.Split
        }
    }

    @Test
    fun tabletJava() {
        shot("tablet10-java.png", SampleProject.REPO, w = 2560, h = 1600, showTree = true,
            typeAt = "result = new " to "Arr") { }
    }

    @Test
    fun tabletAgent() {
        val b = object : ShotBackend() { override val agent: AgentService = SampleAgent.service() }
        shot("tablet10-agent.png", SampleProject.NOTE_CARD, w = 2560, h = 1600, backend = b) { selectedRightPanel = "agent.chat" }
    }

    @Test
    fun tablet7Build() {
        val b = object : ShotBackend() {
            override val buildState: StateFlow<BuildState> = MutableStateFlow(SampleBuild.succeeded())
        }
        shot("tablet7-build.png", SampleProject.NOTE_CARD, w = 1920, h = 1200, backend = b, host = NotesPreviewHost, density = 1.5f) {
            active!!.viewMode = EditorViewMode.Split
            consoleOpen = true
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private open inner class ShotBackend : StubBackend() {
        override val project = ProjectInfo("Aurora Notes", ROOT, 1, isAndroid = true)
        override fun fileTree(mode: TreeViewMode): TreeNode = SampleProject.tree()
        override fun readFile(path: String): String = SampleProject.files[path] ?: ""
        override fun moduleNameForFile(path: String): String? = "app"
        override suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> =
            if (path.endsWith(".kt")) KotlinTokens.of(text) else emptyList()
        override fun supported(): Boolean = true
        override suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> {
            val first = text.indexOf("\nimport ")
            if (first < 0) return emptyList()
            val start = first + 1 + "import ".length
            val lastLine = text.lastIndexOf("\nimport ")
            val end = text.indexOf('\n', lastLine + 1)
            return listOf(UiFoldRegion(start, end, "...", "imports", collapsedByDefault = true))
        }
        override suspend fun composePreviews(path: String, text: String): List<UiComposePreview> =
            if (path.endsWith("NoteCard.kt")) listOf(UiComposePreview("NoteListPreview", text.indexOf("fun NoteListPreview"),
                config = UiPreviewConfig(showBackground = true))) else emptyList()
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
            var start = offset
            while (start > 0 && text[start - 1].isLetterOrDigit()) start--
            val prefix = text.substring(start, offset)
            val pool = if (path.endsWith(".java")) SampleCompletion.javaTypes else SampleCompletion.kotlinListMembers
            return UiCompletionResult(pool.filter { it.label.startsWith(prefix) }, start, offset)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shot(
        name: String,
        path: String,
        w: Int = 822,
        h: Int = 1780,
        backend: StubBackend = ShotBackend(),
        host: ComposePreviewHost? = null,
        frames: Int = 70,
        typeAt: Pair<String, String>? = null,
        showTree: Boolean = false,
        density: Float = 2f,
        setup: IdeUiState.() -> Unit,
    ) {
        val state = IdeUiState(backend, composePreviewHost = host, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking {
            state.ensureTreeLoaded()
            for (other in SampleProject.tabs) if (other != path) state.openSuspend(other, other.substringAfterLast('/'))
            state.openSuspend(path, path.substringAfterLast('/'))
            state.activeIndex = state.openFiles.indexOfFirst { it.path == path }
        }
        state.editorFontScale = 0.84f
        state.consoleOpen = false
        val treePanel = state.selectedLeftPanel ?: "project"
        state.selectedLeftPanel = if (showTree) treePanel else null
        if (showTree) listOf("root", "app", "kotlin", "pkg", "pkg.ui", "java").forEach { state.treeExpanded[it] = true }
        state.setup()
        val scene = ImageComposeScene(width = w, height = h, density = Density(density)) {
            CodeAssistTheme(dark = true, codeFont = rememberJetBrainsMono()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    EditorScreen(state, onToggleTheme = {})
                }
            }
        }
        try {
            var img = scene.render()
            for (f in 1..frames) {
                Thread.sleep(25)
                img = scene.render(f * 16_666_667L)
                if (f == 25 && typeAt != null) {
                    val session = state.active!!.session
                    val at = session.doc.text.indexOf(typeAt.first) + typeAt.first.length
                    session.setCaret(at)
                    session.commitText(typeAt.second)
                }
            }
            img = scene.render(frames * 16_666_667L + 2_000_000_000L)
            File(OUT_DIR).mkdirs()
            File("$OUT_DIR/$name").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote $OUT_DIR/$name")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val ROOT = "/p/AuroraNotes"
        val OUT_DIR: String = System.getenv("SNAPSHOT_DIR")
            ?: File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/store").absolutePath
    }
}

/** A small, believable Android + Compose project. */
internal object SampleProject {
    const val PKG_DIR = "/p/AuroraNotes/app/src/main/kotlin/com/aurora/notes"
    const val NOTE_CARD = "$PKG_DIR/ui/NoteCard.kt"
    const val MAIN = "$PKG_DIR/MainActivity.kt"
    const val VIEW_MODEL = "$PKG_DIR/NotesViewModel.kt"
    const val REPO = "/p/AuroraNotes/app/src/main/java/com/aurora/notes/data/NoteRepository.java"
    const val GRADLE = "/p/AuroraNotes/app/build.gradle.kts"
    val tabs = listOf(NOTE_CARD, VIEW_MODEL, MAIN)

    val files = mapOf(
        NOTE_CARD to """
package com.aurora.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aurora.notes.Note

@Composable
fun NoteCard(
    note: Note,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleMedium)
                Text(note.body, maxLines = 2)
            }
            Checkbox(note.done, onCheckedChange = { onToggle() })
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NoteListPreview() {
    AuroraTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sampleNotes.forEach { NoteCard(it, onToggle = {}) }
        }
    }
}
""".trimStart(),
        MAIN to """
package com.aurora.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aurora.notes.ui.NotesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NotesScreen() }
    }
}
""".trimStart(),
        VIEW_MODEL to """
package com.aurora.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class NotesViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = repository.notes
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pinned: StateFlow<List<Note>> = notes
        .map { notes -> notes. }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggle(note: Note) = viewModelScope.launch {
        repository.update(note.copy(done = !note.done))
    }

    suspend fun search(query: String): List<Note> =
        notes.value.filter { query in it.title }
}
""".trimStart(),
        REPO to """
package com.aurora.notes.data;

import androidx.annotation.NonNull;
import java.util.List;

public final class NoteRepository {
    private final NoteDao dao;

    public NoteRepository(@NonNull NoteDao dao) {
        this.dao = dao;
    }

    @NonNull
    public List<Note> pinned() {
        List<Note> result = new ();
        for (Note note : dao.all()) {
            if (note.isPinned()) result.add(note);
        }
        return result;
    }
}
""".trimStart(),
        GRADLE to """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aurora.notes"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aurora.notes"
        minSdk = 26
        versionCode = 12
        versionName = "1.4.0"
    }
}
""".trimStart(),
    )

    private fun file(path: String, icon: String) =
        TreeNode(path, path.substringAfterLast('/'), NodeKind.File, path, iconId = icon)

    fun tree(): TreeNode = TreeNode(
        "root", "Aurora Notes", NodeKind.Workspace, null,
        children = listOf(
            TreeNode(
                "app", "app", NodeKind.Module, null, iconId = "module.android",
                children = listOf(
                    TreeNode(
                        "kotlin", "kotlin", NodeKind.SourceRoot, null, iconId = "sourceset.kotlin",
                        children = listOf(
                            TreeNode(
                                "pkg", "com.aurora.notes", NodeKind.Package, null, iconId = "package",
                                children = listOf(
                                    TreeNode(
                                        "pkg.ui", "ui", NodeKind.Package, null, iconId = "package",
                                        children = listOf(file(NOTE_CARD, "kotlin")),
                                    ),
                                    file(MAIN, "kotlin"),
                                    file(VIEW_MODEL, "kotlin"),
                                ),
                            ),
                        ),
                    ),
                    TreeNode(
                        "java", "java", NodeKind.SourceRoot, null, iconId = "sourceset.java",
                        children = listOf(file(REPO, "java")),
                    ),
                    file(GRADLE, "gradle"),
                ),
            ),
        ),
    )
}

/** Approximate type-aware tokens for the sample Kotlin, standing in for the language backend. */
internal object KotlinTokens {
    private val composables = setOf(
        "NoteCard", "NoteListPreview", "ElevatedCard", "Row", "Column", "Text", "Checkbox", "AuroraTheme",
        "NotesScreen", "setContent",
    )
    private val keywords = setOf(
        "package", "import", "fun", "val", "var", "class", "object", "return", "if", "else", "when", "in",
        "private", "override", "suspend", "true", "false", "null", "this", "super", "is", "as", "public",
    )

    fun of(text: String): List<UiSemanticToken> {
        val out = mutableListOf<UiSemanticToken>()
        val lines = text.split('\n')
        var base = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("package") && !trimmed.startsWith("import") && !trimmed.startsWith("//")) {
                Regex("""(?<![\w@"])([A-Za-z_]\w*)""").findAll(line).forEach { m ->
                    val name = m.value
                    if (name in keywords) return@forEach
                    // skip inside a string literal
                    if (line.substring(0, m.range.first).count { it == '"' } % 2 == 1) return@forEach
                    val s = base + m.range.first
                    val e = base + m.range.last + 1
                    val before = line.substring(0, m.range.first).trimEnd()
                    val after = line.substring(m.range.last + 1).trimStart()
                    val calls = after.startsWith("(") || after.startsWith("{")
                    val declFun = before.endsWith("fun")
                    val declVal = before.endsWith("val") || before.endsWith("var")
                    val token = when {
                        declFun -> UiSemanticToken(s, e, "function", buildSet {
                            add(UiHighlightModifier.Declaration)
                            if (name in composables) add(UiHighlightModifier.Composable)
                            if (before.contains("suspend")) add(UiHighlightModifier.Suspend)
                        })
                        declVal && line.startsWith("    ") && !line.startsWith("        ") ->
                            UiSemanticToken(s, e, "property", setOf(UiHighlightModifier.Declaration))
                        declVal -> UiSemanticToken(s, e, "localVariable", setOf(UiHighlightModifier.Declaration))
                        name in composables && calls -> UiSemanticToken(s, e, "function", setOf(UiHighlightModifier.Composable))
                        before.endsWith(".") && calls -> UiSemanticToken(s, e, "method")
                        before.endsWith(".") && name[0].isUpperCase() && !after.startsWith(".") ->
                            UiSemanticToken(s, e, "enumConstant", setOf(UiHighlightModifier.Static))
                        before.endsWith(".") -> UiSemanticToken(s, e, "property")
                        after.startsWith("=") && !after.startsWith("==") && before.endsWith(",") || after.startsWith("=") && before.endsWith("(") ->
                            UiSemanticToken(s, e, "parameter")
                        name[0].isUpperCase() && calls -> UiSemanticToken(s, e, "constructor")
                        name[0].isUpperCase() -> UiSemanticToken(s, e, "class")
                        calls -> UiSemanticToken(s, e, "function")
                        name == "it" -> UiSemanticToken(s, e, "parameter")
                        after.startsWith(":") -> UiSemanticToken(s, e, "parameter", setOf(UiHighlightModifier.Declaration))
                        else -> null
                    }
                    if (token != null) out += token
                }
            }
            base += line.length + 1
        }
        return out
    }
}

internal object SampleCompletion {
    private fun m(label: String, detail: String, doc: String? = null, prio: Int = 0) =
        UiCompletionItem(label, label, detail, "kotlin.collections", doc, UiCompletionKind.Method, prio)

    val kotlinListMembers = listOf(
        m("filter", "{ predicate: (Note) -> Boolean }: List<Note>",
            "Returns a list containing only elements matching the given predicate."),
        m("filterNot", "{ predicate: (Note) -> Boolean }: List<Note>"),
        m("filterIndexed", "{ (index: Int, Note) -> Boolean }: List<Note>"),
        m("filterIsInstance", "<R>(): List<R>"),
        m("filterTo", "(destination: C) { (Note) -> Boolean }: C"),
        m("find", "{ predicate: (Note) -> Boolean }: Note?"),
        m("first", "(): Note"),
        m("firstOrNull", "{ predicate: (Note) -> Boolean }: Note?"),
    )

    private fun c(label: String, pkg: String, kind: UiCompletionKind = UiCompletionKind.Class, doc: String? = null) =
        UiCompletionItem(label, label, null, pkg, doc, kind, 0)

    val javaTypes = listOf(
        c("ArrayList", "java.util", doc = "Resizable-array implementation of the List interface. Implements all optional " +
            "list operations, and permits all elements, including null. Each ArrayList instance has a capacity: the size " +
            "of the array used to store the elements in the list. As elements are added, its capacity grows automatically."),
        c("ArrayDeque", "java.util"),
        c("Arrays", "java.util"),
        c("ArrayMap", "androidx.collection"),
        c("ArraySet", "androidx.collection"),
        c("ArrayIndexOutOfBoundsException", "java.lang"),
        c("ArrayStoreException", "java.lang"),
    )
}

internal object SampleBuild {
    fun succeeded(): BuildState {
        val steps = listOf(
            "Resolve dependencies", "Compile resources (aapt2)", "Generate R", "Compile Kotlin",
            "Compile Java", "Dex (D8)", "Package APK", "Sign APK", "Install",
        ).map { BuildStepUi(it, StepStatus.Done) }
        var t = 0
        fun line(task: String?, msg: String, level: UiLogLevel = UiLogLevel.Info): BuildLogLine {
            t += 380
            return BuildLogLine(msg, level, task, timeLabel = "14:03:%02d.%03d".format(12 + t / 1000, t % 1000))
        }
        val log = listOf(
            line(":app:compileResources", "aapt2: linked 128 resources"),
            line(":app:compileKotlin", "kotlinc: 14 files, Compose compiler plugin"),
            line(":app:dex", "d8: 3 classes changed, 1,204 cached"),
            line(":app:packageDebug", "app-debug.apk, 4.2 MB"),
            line(":app:signDebug", "v2 + v3 signatures"),
            line(":app:install", "Installed com.aurora.notes"),
            line(null, "BUILD SUCCESSFUL in 18.4s"),
        )
        return BuildState(RunStatus.Succeeded, "app", steps, log, emptyList(), elapsedMs = 18_400)
    }
}

internal object SampleAgent {
    private val change = UiAgentFileChange(
        "app/src/main/kotlin/com/aurora/notes/ui/NoteCard.kt",
        "            Checkbox(note.done, onCheckedChange = { onToggle() })\n",
        "            IconToggleButton(note.pinned, onCheckedChange = { onPin() }) {\n" +
            "                Icon(Icons.Rounded.PushPin, contentDescription = \"Pin\")\n" +
            "            }\n" +
            "            Checkbox(note.done, onCheckedChange = { onToggle() })\n",
    )

    fun state() = UiAgentChatState(
        messages = listOf(
            UiAgentMessage(1, UiAgentRole.USER, text = "Add a pin button to NoteCard and keep pinned notes on top."),
            UiAgentMessage(
                2, UiAgentRole.ASSISTANT,
                toolCalls = listOf(
                    UiAgentToolCall("a", "read NoteCard.kt", UiAgentToolStatus.OK, "46 lines"),
                    UiAgentToolCall("b", "edit NoteCard.kt", UiAgentToolStatus.OK, "Edited NoteCard.kt", listOf(change)),
                    UiAgentToolCall("c", "edit NotesViewModel.kt", UiAgentToolStatus.OK, "Sorted pinned first"),
                    UiAgentToolCall("d", "build :app", UiAgentToolStatus.OK, "BUILD SUCCESSFUL in 9.2s"),
                ),
                text = "Done. `NoteCard` now has a pin toggle next to the checkbox, and `NotesViewModel` " +
                    "sorts pinned notes first with `sortedByDescending { it.pinned }`. The app builds cleanly.",
            ),
        ),
        todos = listOf(
            UiAgentTodo("Add a pin toggle to NoteCard", UiAgentTodoStatus.DONE),
            UiAgentTodo("Sort pinned notes first", UiAgentTodoStatus.DONE),
            UiAgentTodo("Build and verify", UiAgentTodoStatus.DONE),
        ),
    )

    fun service(): AgentService = object : AgentService {
        override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(state())
        override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
        override val models: StateFlow<List<UiAgentModel>> =
            MutableStateFlow(listOf(UiAgentModel("claude-opus-5", "Opus 5")))
        override fun config(): UiAgentConfig = UiAgentConfig(
            providers = listOf(UiAgentProvider("anthropic", "Anthropic (Claude)",
                listOf(UiAgentModel("claude-opus-5", "Opus 5")), "claude-opus-5", "sk-live")),
            selectedProvider = "anthropic", model = "claude-opus-5", configured = true,
            mode = UiAgentPermissionMode.AUTO_ACCEPT,
        )
        override fun refreshModels() {}
        override fun setModel(model: String) {}
        override fun selectProvider(id: String) {}
        override fun setProviderKey(providerId: String, key: String) {}
        override fun setGateway(baseUrl: String, model: String, caCert: String) {}
        override fun send(text: String) {}
        override fun retry() {}
        override fun stop() {}
        override fun newSession() {}
        override fun setPermissionMode(mode: UiAgentPermissionMode) {}
        override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
    }
}

/** Renders the sample's NoteListPreview for real with Material 3, standing in for the on-device interpreter. */
internal object NotesPreviewHost : ComposePreviewHost {
    private data class Note(val title: String, val body: String, val done: Boolean)
    private val notes = listOf(
        Note("Ship v1.4 to beta", "Changelog, screenshots, and the pinned notes fix.", false),
        Note("Groceries", "Oat milk, lemons, basil, sourdough.", true),
        Note("Book club", "Finish chapter 9 before Thursday.", false),
    )
    private val scheme = androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF4F5B92),
        surface = androidx.compose.ui.graphics.Color(0xFFFBF8FF),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF4F2FA),
        background = androidx.compose.ui.graphics.Color(0xFFFBF8FF),
    )

    @Composable
    override fun Preview(
        path: String, preview: UiComposePreview, text: String, dark: Boolean,
        onProblems: (List<PreviewIssue>) -> Unit, onBusy: (Boolean) -> Unit, modifier: Modifier,
    ) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onProblems(emptyList()); onBusy(false) }
        MaterialTheme(colorScheme = scheme) {
            androidx.compose.foundation.layout.Column(
                modifier.background(scheme.surface).padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                notes.forEach { note ->
                    androidx.compose.material3.ElevatedCard(Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(note.title, style = MaterialTheme.typography.titleMedium)
                                androidx.compose.material3.Text(note.body, maxLines = 2,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.Checkbox(note.done, onCheckedChange = {})
                        }
                    }
                }
            }
        }
    }
}
