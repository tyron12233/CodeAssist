package dev.ide.android.support

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor-facing ViewBinding provider: gated on `buildFeatures { viewBinding }`, it surfaces one synthetic
 * `<namespace>.databinding.<Layout>Binding` per layout so a binding resolves for completion before a build.
 */
class AndroidViewBindingProviderTest {

    private fun withApp(viewBinding: Boolean, block: (Module, Workspace) -> Unit) {
        val dir = createTempDirectory("vb-provider")
        val platform = PlatformCore()
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(moduleTypes, codecs)
        val store = ProjectModel.open(dir, platform, codecs)
        try {
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", moduleTypes.resolve("android-app")).apply {
                    putFacet(
                        AndroidFacet(
                            namespace = "com.example.app", compileSdk = 34,
                            buildFeatures = BuildFeatures(viewBinding = viewBinding),
                        ),
                    )
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

    /** Write `layout/<name>.xml` into the module's own ANDROID_RES root (where the provider reads). */
    private fun writeLayout(module: Module, name: String, xml: String) {
        val resDir = module.sourceSets.flatMap { it.contentRoots }
            .first { ContentRole.ANDROID_RES in it.roles }.dir.path
        val layoutDir = Paths.get(resDir).resolve("layout")
        Files.createDirectories(layoutDir)
        layoutDir.resolve("$name.xml").writeText(xml)
    }

    private fun classesFor(module: Module, workspace: Workspace): List<SyntheticClass> {
        val ctx = object : SyntheticClassContext {
            override val module = module
            override val workspace = workspace
        }
        return AndroidViewBindingProvider().classesFor(ctx)
    }

    @Test
    fun `surfaces a binding class per layout when enabled`() = withApp(viewBinding = true) { module, workspace ->
        writeLayout(
            module, "activity_main",
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/greeting"/>
            </LinearLayout>
            """.trimIndent(),
        )
        val binding = classesFor(module, workspace).single { it.fqName == "com.example.app.databinding.ActivityMainBinding" }
        assertTrue(SyntheticModifier.FINAL in binding.modifiers)
        // The view field is an instance field (NOT static, unlike R's int constants).
        val field = binding.fields.single { it.name == "greeting" }
        assertEquals("android.widget.TextView", field.type)
        assertTrue(SyntheticModifier.STATIC !in field.modifiers)
        // The factory + accessor methods are present.
        val methodNames = binding.methods.map { it.name }.toSet()
        assertTrue(methodNames.containsAll(setOf("getRoot", "inflate", "bind")))
    }

    @Test
    fun `emits nothing when viewBinding is off`() = withApp(viewBinding = false) { module, workspace ->
        writeLayout(
            module, "activity_main",
            """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"/>""",
        )
        assertTrue(classesFor(module, workspace).isEmpty())
    }
}
