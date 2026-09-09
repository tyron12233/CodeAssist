package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCodeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * State and intents for the Code Style screen: the language being edited, its persisted profile, and the
 * live formatter preview. Every control edit persists immediately and flips the preset to Custom, so the
 * change takes effect without a save step.
 */
@Stable
internal class CodeStyleScreenState(
    private val backend: IdeBackend,
    private val hasProject: Boolean,
    private val scope: CoroutineScope,
) {
    var language: String by mutableStateOf(LANG_JAVA)
        private set
    var style: UiCodeStyle by mutableStateOf(UiCodeStyle())
        private set
    var formatOnSave: Boolean by mutableStateOf(backend.settings.settings().formatOnSave)
        private set

    /** The sample re-formatted with the current profile, or blank when there is no engine to format with. */
    var preview: String by mutableStateOf("")
        private set

    /** The profile as the controls show it (a preset resolves to its concrete values). */
    val display: UiCodeStyle get() = displayStyle(style)
    val javaOnly: Boolean get() = language == LANG_JAVA
    val custom: Boolean get() = style.preset == PRESET_CUSTOM

    init {
        // Load the persisted profile when the language changes.
        scope.launch {
            snapshotFlow { language }.collect { style = backend.settings.codeStyle(it) }
        }
        // Live preview: re-format the sample shortly after the profile settles (`collectLatest` cancels the
        // prior run, which debounces rapid slider drags). The formatter is engine-backed, so it is skipped
        // with no project open.
        scope.launch {
            snapshotFlow { language to style }.collectLatest { (lang, current) ->
                if (!hasProject) {
                    preview = ""
                    return@collectLatest
                }
                delay(120)
                preview = runCatching { backend.settings.formatStylePreview(lang, current) }.getOrDefault("")
            }
        }
    }

    fun selectLanguage(value: String) { language = value }

    /** Persist and adopt a whole profile (a preset pick, or Custom seeded from what is showing). */
    fun update(next: UiCodeStyle) {
        style = next
        backend.settings.setCodeStyle(language, next)
    }

    /** Edit one control: the change is applied to the resolved profile, which becomes Custom. */
    fun edit(transform: UiCodeStyle.() -> UiCodeStyle) {
        update(display.transform().copy(preset = PRESET_CUSTOM))
    }

    fun updateFormatOnSave(value: Boolean) {
        formatOnSave = value
        backend.settings.setPreference(FORMAT_ON_SAVE_KEY, value.toString())
    }
}

@Composable
internal fun rememberCodeStyleScreenState(
    backend: IdeBackend,
    hasProject: Boolean,
    scope: CoroutineScope = rememberCoroutineScope(),
): CodeStyleScreenState = remember(backend, hasProject, scope) {
    CodeStyleScreenState(backend, hasProject, scope)
}
