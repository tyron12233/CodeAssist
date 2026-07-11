package dev.ide.android.support

import dev.ide.android.support.templates.JetpackComposeAppTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.Workspace
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.TemplateArgs
import dev.ide.platform.impl.PlatformCore
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeTemplateTest {

    @Test
    fun generatesProguardRulesFileAtTheModuleRoot() {
        withScaffold { scaffold, root ->
            JetpackComposeAppTemplate.generate(scaffold, args())
            // The default `release` build type references `proguard-rules.pro`; like the other Android
            // templates, the Compose template writes it so the entry resolves and the file is visible in the
            // tree (the curated Project view lists every module-root file).
            val rules = root.resolve("app/proguard-rules.pro")
            assertTrue(Files.isRegularFile(rules), "proguard-rules.pro is generated at the module root")
            assertEquals(
                "proguard-rules.pro",
                AndroidFacet.DEFAULT_BUILD_TYPES.first { it.name == "release" }.proguardFiles
                    .first { !DefaultProguardFiles.isDefault(it) },
                "the file matches the release build type's module-relative proguardFiles entry",
            )
        }
    }

    // --- support ---

    private fun args(extra: Map<String, String> = emptyMap()) = TemplateArgs(
        mapOf(TemplateArgs.NAME to "ComposeApp", TemplateArgs.PACKAGE to "com.example.app") + extra,
    )

    private fun withScaffold(body: (ProjectScaffold, root: java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("compose-template")
        val platform = PlatformCore()
        try {
            val store: ProjectModelStore = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val scaffold = object : ProjectScaffold {
                override val workspace: Workspace get() = store.workspace
                override val rootDir: VirtualFile get() = store.vfs.root()
                override val languageLevel: LanguageLevel = LanguageLevel.JAVA_17
                override fun moduleType(id: String): ModuleType = ModuleTypeRegistry(platform.extensions).resolve(id)
                override fun writeText(relPath: String, content: String) {
                    val file = store.rootPath.resolve(relPath)
                    Files.createDirectories(file.parent)
                    file.writeText(content.trimIndent() + "\n")
                }
                override fun writeBytes(relPath: String, bytes: ByteArray) {
                    val file = store.rootPath.resolve(relPath)
                    Files.createDirectories(file.parent)
                    Files.write(file, bytes)
                }
            }
            body(scaffold, store.rootPath)
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }
}
