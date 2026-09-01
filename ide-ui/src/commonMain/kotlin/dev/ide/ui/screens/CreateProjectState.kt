package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiProjectTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Create-Project form for one template: the name and package, the template's own parameters, and the
 * create itself. The package auto-derives from the name until the user edits it.
 */
@Stable
internal class CreateProjectFormState(
    private val backend: IdeBackend,
    private val template: UiProjectTemplate,
    private val scope: CoroutineScope,
) {
    var name: String by mutableStateOf(defaultName(template))
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var typedPackage by mutableStateOf("")
    private var packageEdited by mutableStateOf(false)

    /** One value per template parameter, seeded from the template's defaults. */
    val paramValues = mutableStateMapOf<String, String>().apply {
        template.parameters.forEach { p -> put(p.key, defaultValue(p)) }
    }

    /** What the package field shows: the user's own text once they edit it, otherwise derived from the name. */
    val packageName: String
        get() = if (packageEdited) typedPackage else "com.example.${slug(name).replace("-", "")}".ifEmpty { "com.example.app" }

    val nameValid: Boolean get() = name.isNotBlank()
    val packageValid: Boolean
        get() = packageName.isNotBlank() && packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    val canCreate: Boolean get() = nameValid && packageValid && !busy

    fun updateName(value: String) { name = value }

    fun updatePackage(value: String) {
        typedPackage = value
        packageEdited = true
    }

    fun updateParam(key: String, value: String) { paramValues[key] = value }

    /** Create the project from the template; [onCreated] moves on to the editor when it lands. */
    fun create(onCreated: () -> Unit) {
        if (!canCreate) return
        busy = true
        error = null
        val args = HashMap<String, String>().apply {
            put("name", name.trim())
            put("packageName", packageName.trim().trim('.'))
            paramValues.forEach { (key, value) -> put(key, value) }
        }
        scope.launch {
            val result = backend.projects.createProject(template.id, args)
            busy = false
            if (result.success) onCreated() else error = result.message
        }
    }
}

@Composable
internal fun rememberCreateProjectFormState(
    backend: IdeBackend,
    template: UiProjectTemplate,
    scope: CoroutineScope = rememberCoroutineScope(),
): CreateProjectFormState = remember(backend, template, scope) {
    CreateProjectFormState(backend, template, scope)
}
