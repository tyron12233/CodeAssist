package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for the desktop preview failure `NullPointerException: invoke getParent on null` on a plain
 * `Scaffold(topBar = { TopAppBar(…) }, bottomBar = { NavigationBar { … } })`. The desktop host interprets the
 * project's `material3-android`, whose `ScaffoldDefaults.contentWindowInsets` reads `WindowInsets.systemBars`:
 * the Android-only facade `WindowInsets_androidKt`, which the host lacks, so the VM interpreted it too and it
 * reached `WindowInsetsHolder.current()` → `LocalView.current` (no View on desktop) → `view.getParent()`.
 * [PlatformFacadeRedirect] sends such calls to the host's own `actual` (`WindowInsets_notMobileKt`).
 */
class AndroidMaterial3ScaffoldReproTest {

    private val redirect = PlatformFacadeRedirect(
        javaClass.classLoader,
        hostLoadable = { runCatching { Class.forName(it, false, javaClass.classLoader) }.isSuccess },
        keepInterpreted = { it.startsWith("androidx.compose.material3.") },
    )
    private val insetsGetter = "(Landroidx/compose/foundation/layout/WindowInsets\$Companion;Landroidx/compose/runtime/Composer;I)Landroidx/compose/foundation/layout/WindowInsets;"

    @Test
    fun androidOnlyInsetsFacadeRedirectsToTheHostActual() {
        assertEquals(
            "androidx/compose/foundation/layout/WindowInsets_notMobileKt",
            redirect.hostOwner("androidx/compose/foundation/layout/WindowInsets_androidKt", "getSystemBars", insetsGetter),
        )
    }

    @Test
    fun aDeclarationTheHostLacksKeepsInterpreting() {
        // Android-only API with no skiko `actual`.
        assertNull(redirect.hostOwner("androidx/compose/foundation/layout/WindowInsets_androidKt", "getSystemBarsIgnoringVisibility", insetsGetter))
        // Same name, different descriptor.
        assertNull(redirect.hostOwner("androidx/compose/foundation/layout/WindowInsets_androidKt", "getSystemBars", "()V"))
        // Not an Android facade at all.
        assertNull(redirect.hostOwner("androidx/compose/foundation/layout/WindowInsetsKt", "getSystemBars", insetsGetter))
        // A project-preferred namespace stays interpreted: the host's types are not the ones its caller expects.
        assertNull(redirect.hostOwner("androidx/compose/material3/SystemBarsDefaultInsets_androidKt", "getSystemBarsForVisualComponents", insetsGetter))
    }

    /** The project's Android library jars, as `composePreviewLibs` hands them to the desktop host; null when the
     *  local Gradle cache doesn't hold them (the render test then has nothing to interpret and is skipped). */
    private fun projectJars(): List<Path>? {
        val cache = Paths.get(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        val coords = listOf(
            "androidx.compose.material3/material3-android/1.5.0-alpha24",
            "androidx.compose.foundation/foundation-layout-android/1.12.0-beta01",
            "androidx.compose.foundation/foundation-android/1.12.0-beta01",
            "androidx.compose.ui/ui-android/1.12.0-beta01",
            "androidx.compose.ui/ui-text-android/1.12.0-beta01",
            "androidx.compose.ui/ui-graphics-android/1.12.0-beta01",
            "androidx.compose.ui/ui-unit-android/1.12.0-beta01",
            "androidx.compose.ui/ui-util-android/1.12.0-beta01",
            "androidx.compose.animation/animation-android/1.12.0-beta01",
            "androidx.compose.animation/animation-core-android/1.12.0-beta01",
            "androidx.compose.material/material-ripple-android/1.12.0-beta01",
            "androidx.core/core/1.16.0",
        )
        return coords.map { c ->
            val dir = cache.resolve(c)
            if (!Files.isDirectory(dir)) return null
            val art = Files.walk(dir).use { s -> s.filter { val n = it.fileName.toString(); (n.endsWith(".aar") || n.endsWith(".jar")) && !n.contains("sources") }.findFirst().orElse(null) } ?: return null
            if (art.toString().endsWith(".jar")) art else {
                val out = Files.createTempFile(c.substringAfter('/').replace('/', '-'), ".jar").also { it.toFile().deleteOnExit() }
                ZipFile(art.toFile()).use { z -> z.getInputStream(z.getEntry("classes.jar")).use { Files.copy(it, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING) } }
                out
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun scaffoldWithTopAppBarAndNavigationBarRenders() {
        val jars = projectJars() ?: return
        val code = """
            package com.example.compose

            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            import androidx.compose.runtime.*
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import androidx.compose.foundation.background
            import androidx.compose.ui.graphics.Color

            @OptIn(ExperimentalMaterial3Api::class)
            @Composable
            fun AdvancedAppScreen() {
                var selectedItem by remember { mutableIntStateOf(0) }
                val items = listOf("Home", "Settings", "Another Tab")
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Advanced Material UI") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            items.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedItem == index,
                                    onClick = { selectedItem = index },
                                    label = { Text(item) },
                                    icon = { Text("I") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .background(Color.Red)
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Text(text = "Selected Section: ${'$'}{items[selectedItem]}", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                            Text("Advanced Material 3 Content", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val entry = program.getValue("AdvancedAppScreen/0")
        assertTrue(entry.isComplete, "the preview must lower; diags=${entry.diagnostics}")
        var hard: Throwable? = null
        val partials = java.util.Collections.synchronizedList(mutableListOf<Throwable?>())
        val renderer = ComposePreviewRenderer(libraryExecutor = VmLibraryExecutor(jars))
        val content: @Composable () -> Unit = {
            renderer.Render(entry, program, classes, emptyList(), onError = { hard = it }, onPartialError = { partials.add(it) })
        }
        val scene = ImageComposeScene(400, 800, Density(1f), content = content)
        val center = try {
            scene.render(0L)
            Bitmap.makeFromImage(scene.render(16_000_000L)).getColor(200, 400)
        } finally { scene.close() }
        assertTrue(hard == null && partials.filterNotNull().isEmpty(), "no render errors; hard=$hard partials=${partials.filterNotNull()}")
        assertEquals(0xFFFF0000.toInt(), center, "the Scaffold body (a red Column) must paint between the bars")
    }

    private class MemDir(private val kids: List<VirtualFile>) : VirtualFile {
        override val path = "src"; override val name = "src"; override val isDirectory = true
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = kids
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
    private class MemFile(override val name: String, private val content: String) : VirtualFile {
        override val path = name; override val isDirectory = false; override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }
    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = MemFile("Main.kt", text.toString()); override val version = 1L
        override fun length() = text.length
    }
}
