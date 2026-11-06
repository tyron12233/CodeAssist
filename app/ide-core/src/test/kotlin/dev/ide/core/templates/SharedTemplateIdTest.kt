package dev.ide.core.templates

import dev.ide.core.ApplicationEnvironment
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.platform.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Two plugins contributing a template under one id: the published Dart plugin's `dart-console`, and a Dart plugin
 * someone was developing with an id of its own and the same template id. Both loaded, the Create-Project gallery
 * got the id twice, and its grid (keyed on template ids) crashed the app.
 */
class SharedTemplateIdTest {

    private class Template(id: String, override val displayName: String) : ProjectTemplate {
        override val id = TemplateId(id)
        override val description = ""
        override val category = TemplateCategory.OTHER
        override val iconId = ""
        override fun parameters(): List<TemplateParameter> = emptyList()
        override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) = Unit
    }

    @Test
    fun aTemplateIdIsListedOnceAndTheFirstRegisteredWins() {
        ApplicationEnvironment().use { env ->
            val published = Template("dart-console", "Dart Console App")
            val inDevelopment = Template("dart-console", "My Dart Console")
            env.platform.extensions.register(ProjectTemplateExtensionPoint, published, PluginId("dev.codeassist.dart"))
            env.platform.extensions.register(ProjectTemplateExtensionPoint, inDevelopment, PluginId("com.example.mydart"))

            val registry = ProjectTemplateRegistry(env.platform.extensions)
            val dart = registry.all().filter { it.id.value == "dart-console" }
            assertEquals(1, dart.size)
            assertSame(published, dart.single())
            assertSame(published, registry.byId(TemplateId("dart-console")), "the gallery and creation agree")
            assertEquals(registry.all().size, registry.all().map { it.id }.distinct().size, "no id twice")
        }
    }
}
