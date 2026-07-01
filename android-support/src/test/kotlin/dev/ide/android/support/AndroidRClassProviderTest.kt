package dev.ide.android.support

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceModel
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.jdt.synthetic.SyntheticJavaSource
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The synthetic `R` an Android module resolves must emit as compilable Java — specifically its nested
 * subclasses must be `public static final` like real `R.java`. A non-static inner class can't hold a static
 * field that isn't a constant variable, and `R.styleable`'s `int[]` arrays are exactly that, so without
 * `static` the layout-preview's custom-view compile (pinned to source 8) rejects the synthetic `R` and no
 * custom view renders. This guards that regression at both the structural and compile levels.
 */
class AndroidRClassProviderTest {

    // A repo with the shapes a custom view touches: a styleable (int[] + index constants) and a plain type.
    private val repo = ResourceRepository(
        items = listOf(
            ResourceItem(ResourceType.COLOR, "primary", value = "#FF6200EE"),
            ResourceItem(ResourceType.ATTR, "barColor"),
            ResourceItem(ResourceType.ATTR, "maxValue"),
            ResourceItem(ResourceType.STYLEABLE, "MyChart"),
        ),
        styleableAttrs = mapOf("MyChart" to listOf("barColor", "maxValue")),
    )
    private val model = object : ResourceModel {
        override fun parse(resDirs: List<Path>): ResourceRepository = repo
    }

    private fun withAppModule(block: (Module, Workspace) -> Unit) {
        val dir = createTempDirectory("r-class")
        val platform = PlatformCore()
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(moduleTypes, codecs)
        val store = ProjectModel.open(dir, platform, codecs)
        try {
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", moduleTypes.resolve("android-app")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34))
                }
                commit()
            }
            val app = store.workspace.projects.single().modules.first { it.name == "app" }
            block(app, store.workspace)
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    private fun rClass(module: Module, workspace: Workspace): SyntheticClass {
        val ctx = object : SyntheticClassContext {
            override val module = module
            override val workspace = workspace
        }
        return AndroidRClassProvider(model).classesFor(ctx).single { it.fqName == "com.example.app.R" }
    }

    @Test
    fun `nested R subclasses are static`() = withAppModule { module, workspace ->
        val r = rClass(module, workspace)
        assertTrue(r.nestedClasses.isNotEmpty(), "R should have nested resource classes")
        for (nested in r.nestedClasses) {
            assertTrue(SyntheticModifier.STATIC in nested.modifiers, "R.${nested.fqName.substringAfterLast('.')} must be static")
        }
    }

    @Test
    fun `synthetic R compiles at source level 8`() = withAppModule { module, workspace ->
        val r = rClass(module, workspace)
        val dir = createTempDirectory("r-compile")
        try {
            val src: Path = dir.resolve("com/example/app/R.java")
            Files.createDirectories(src.parent)
            Files.write(src, SyntheticJavaSource.emit(r).toByteArray())
            // Mirror the preview compile: source level 8 (android.jar is fed as -bootclasspath there, which
            // ecj rejects above compliance 8). R references no framework types, so no bootclasspath is needed.
            val result = JdtBatchCompiler.compile(listOf(src), emptyList(), dir.resolve("out"), sourceLevel = "8")
            assertTrue(result.success, "synthetic R must compile at source 8:\n${result.messages.joinToString("\n")}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `R field names with colons and dots are sanitized to compile`() {
        // A real-world repo: a dotted style name (Theme.App) and a styleable whose child <attr> references a
        // framework attr by prefix (android:textColor). aapt2 emits `Theme_App` / `View_android_textColor`;
        // emitting the raw names produces invalid Java ("Syntax error on token ':'") — the device preview bug.
        val colonRepo = ResourceRepository(
            items = listOf(
                ResourceItem(ResourceType.STYLE, "Theme.App", value = null),
                ResourceItem(ResourceType.ATTR, "android:textColor"),
                ResourceItem(ResourceType.ATTR, "barColor"),
                ResourceItem(ResourceType.STYLEABLE, "View"),
            ),
            styleableAttrs = mapOf("View" to listOf("android:textColor", "barColor")),
        )
        val colonModel = object : ResourceModel {
            override fun parse(resDirs: List<Path>): ResourceRepository = colonRepo
        }
        withAppModule { module, workspace ->
            val ctx = object : SyntheticClassContext {
                override val module = module
                override val workspace = workspace
            }
            val r = AndroidRClassProvider(colonModel).classesFor(ctx).single { it.fqName == "com.example.app.R" }
            val java = SyntheticJavaSource.emit(r)
            assertTrue("Theme_App" in java && "Theme.App" !in java, "dotted style name sanitized to Theme_App: $java")
            assertTrue("View_android_textColor" in java && "android:textColor" !in java, "framework-attr index constant sanitized: $java")

            val dir = createTempDirectory("r-colon")
            try {
                val src = dir.resolve("com/example/app/R.java")
                Files.createDirectories(src.parent)
                Files.write(src, java.toByteArray())
                val result = JdtBatchCompiler.compile(listOf(src), emptyList(), dir.resolve("out"), sourceLevel = "8")
                assertTrue(result.success, "sanitized R must compile:\n${result.messages.joinToString("\n")}")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}
