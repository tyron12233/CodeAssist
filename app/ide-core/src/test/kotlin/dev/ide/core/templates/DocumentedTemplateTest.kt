package dev.ide.core.templates

import dev.ide.core.ApplicationEnvironment
import dev.ide.core.IdeServices
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetData
import dev.ide.model.LanguageLevel
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.platform.PluginId
import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The template written out in `docs/custom-project-templates.md`, compiled and run.
 *
 * A guide whose example does not compile is worse than no guide, and this one is aimed at plugin authors
 * outside the repo who cannot check it against the code the way a contributor can. So the worked example
 * lives here too: it type-checks against the real SPI, it is created exactly as the Create-Project flow
 * creates it, and the assertions are the claims the guide makes about what a template produces.
 *
 * Kept deliberately close to the published text. If the SPI changes shape, this fails, and the guide is
 * wrong in the same way.
 */
class DocumentedTemplateTest {

    /** The guide's example, minus the bundled natives (which need a resource this test does not ship). */
    private object DocumentedTemplate : ProjectTemplate {
        override val id = TemplateId("docs-example-game")
        override val displayName = "Documented Example"
        override val description = "The worked example from docs/custom-project-templates.md."
        override val category = TemplateCategory.ANDROID
        override val iconId = "module.android"

        const val MODULE = "app"

        override fun parameters() = listOf(
            TemplateParameter.Choice(
                key = "minSdk",
                label = "Minimum SDK",
                options = listOf(
                    TemplateParameter.Choice.Option("26", "API 26 · Android 8.0"),
                    TemplateParameter.Choice.Option("21", "API 21 · Android 5.0"),
                ),
                help = "Lowest Android version the game supports.",
            ),
        )

        override fun dependencies(args: TemplateArgs) = listOf(
            TemplateDependency(MODULE, "com.badlogicgames.gdx:gdx:1.14.2"),
            TemplateDependency(MODULE, "com.badlogicgames.gdx:gdx-backend-android:1.14.2"),
        )

        override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
            val pkg = args.packageName
            val path = pkg.replace('.', '/')

            scaffold.workspace.beginModification().apply {
                addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
                commit()
            }
            scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
                addModule(MODULE, scaffold.moduleType("android-app")).apply {
                    languageLevel = scaffold.languageLevel
                    putFacetData(
                        FacetData(
                            "android",
                            linkedMapOf(
                                "namespace" to pkg,
                                "compileSdk" to 36L,
                                "minSdk" to args.int("minSdk", 26).toLong(),
                                "targetSdk" to 36L,
                                "isApplication" to true,
                            ),
                        ),
                    )
                }
                commit()
            }

            scaffold.writeText(
                "$MODULE/src/main/res/values/strings.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">${args.name}</string>
                </resources>
                """,
            )
            scaffold.writeText(
                "$MODULE/src/main/java/$path/MainActivity.java",
                """
                package $pkg;

                import android.app.Activity;

                public class MainActivity extends Activity {
                }
                """,
            )
        }
    }

    private fun create(dir: java.nio.file.Path, args: Map<String, String>): IdeServices {
        val env = ApplicationEnvironment()
        env.platform.extensions.register(
            ProjectTemplateExtensionPoint, DocumentedTemplate, PluginId("com.example.docs"),
        )
        return IdeServices.createProjectAt(
            dir,
            "docs-example-game",
            args,
            IdeServices.defaultDesktopSdk(),
            LanguageLevel.JAVA_17,
            env = env,
        )
    }

    @Test
    fun theDocumentedTemplateProducesTheProjectTheGuideDescribes() {
        withTempDir("docs-template") { dir ->
            create(
                dir,
                mapOf(
                    TemplateArgs.NAME to "Game",
                    TemplateArgs.PACKAGE to "com.example.game",
                    "minSdk" to "21",
                ),
            ).use { ide ->
                val project = ide.store.workspace.projects.single()
                assertEquals("Game", project.name)

                val module = project.modules.single()
                assertEquals(DocumentedTemplate.MODULE, module.name)
                assertEquals("android-app", module.type.id, "the module type is resolved, not re-declared")
                assertEquals(LanguageLevel.JAVA_17, module.languageLevel)

                // The guide's central claim: a facet written by table, with no access to the class that
                // declares it, comes back as that plugin's own typed facet.
                val facet = assertNotNull(
                    module.facets.get(dev.ide.android.support.AndroidFacet.KEY),
                    "the android facet must decode from the table the template wrote",
                )
                assertEquals("com.example.game", facet.namespace)
                assertEquals(21, facet.minSdk, "the Choice value reached the facet")
                assertEquals(36, facet.compileSdk)
                assertTrue(facet.isApplication)

                // Both writers landed where the guide says they land.
                assertTrue(dir.resolve("app/src/main/res/values/strings.xml").toFile().isFile)
                val activity = dir.resolve("app/src/main/java/com/example/game/MainActivity.java")
                assertTrue(activity.toFile().isFile)
                val text = activity.toFile().readText()
                assertTrue(
                    text.startsWith("package com.example.game;"),
                    "writeText trims the common indent, so the literal is not indented on disk:\n$text",
                )
            }
        }
    }

    @Test
    fun aDefaultIsUsedWhenTheUserLeavesAParameterUnset() {
        withTempDir("docs-template-default") { dir ->
            // No minSdk in the args at all: the guide says always read with a default, and this is why.
            create(dir, mapOf(TemplateArgs.NAME to "Game", TemplateArgs.PACKAGE to "com.example.game"))
                .use { ide ->
                    val module = ide.store.workspace.projects.single().modules.single()
                    val facet = assertNotNull(module.facets.get(dev.ide.android.support.AndroidFacet.KEY))
                    assertEquals(26, facet.minSdk, "the declared default, not zero")
                }
        }
    }

    @Test
    fun everyDeclaredDependencyNamesAModuleTheTemplateCreates() {
        withTempDir("docs-template-deps") { dir ->
            val args = TemplateArgs(mapOf(TemplateArgs.NAME to "Game", TemplateArgs.PACKAGE to "com.example.game"))
            create(dir, mapOf(TemplateArgs.NAME to "Game", TemplateArgs.PACKAGE to "com.example.game"))
                .use { ide ->
                    val modules = ide.store.workspace.projects.single().modules.map { it.name }.toSet()
                    // The mistake the guide warns about: a coordinate declared against a module that does not
                    // exist is attached to nothing, with no error anywhere.
                    for (dependency in DocumentedTemplate.dependencies(args)) {
                        assertTrue(
                            dependency.module in modules,
                            "'${dependency.coordinate}' names module '${dependency.module}', which the " +
                                "template never created (it made $modules)",
                        )
                    }
                }
        }
    }
}
