package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.ads.AdController
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSettingControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Settings screen last reported. [Message] comes from the backend; the rest the host localizes. */
internal sealed interface SettingsToast {
    data class Message(val text: String) : SettingsToast
    data object BackupReady : SettingsToast
    data object NotificationsEnabled : SettingsToast
}

/**
 * State and intents for the Settings screen: the live mirror of every control's value, the settings actions,
 * and the result toast. The page descriptors themselves stay in composition, since they carry localized
 * titles and the host's injected ad controls.
 */
@Stable
internal class SettingsScreenState(
    private val backend: IdeBackend,
    private val ads: AdController?,
    private val scope: CoroutineScope,
) {
    /**
     * Each control's value, keyed `pageId.controlKey`. Controls read and write this for instant feedback;
     * every write also persists through the backend.
     */
    val values = mutableStateMapOf<String, String>()

    /**
     * Bumped when a Choice/Toggle changes so pages re-fetch: some pages render conditionally on another
     * control's value (Build Runtime hides the R8 heap slider in In-process mode). Sliders and text fields
     * do not bump it, so a drag never triggers a costly per-step re-fetch.
     */
    var structuralRefresh: Int by mutableStateOf(0)
        private set

    var toast: SettingsToast? by mutableStateOf(null)
        private set

    private var toastJob: Job? = null
    private var seeded = false

    /** Seed the mirror from the descriptors, once: later writes are the source of truth. */
    fun seed(initial: Map<String, String>) {
        if (seeded) return
        seeded = true
        values.putAll(initial)
    }

    fun onStructuralChange() { structuralRefresh++ }

    /** Apply a control edit: mirror it, then persist it (the injected ads toggle has its own store). */
    fun set(pageId: String, key: String, encoded: String, onSettingsChanged: () -> Unit) {
        values["$pageId.$key"] = encoded
        if (ads != null && pageId == PRIVACY_PAGE_ID && key == SHOW_ADS_KEY) {
            ads.updateAdsEnabled(encoded.toBooleanStrictOrNull() ?: true)
        } else {
            backend.settings.setSetting(pageId, key, encoded)
            onSettingsChanged()
        }
    }

    /** Run a settings action that the backend owns; the host handles the platform ones itself. */
    fun invokeAction(pageId: String, action: UiSettingControl.Action) {
        scope.launch {
            backend.settings.invokeSettingAction(pageId, action.key)?.let { show(SettingsToast.Message(it)) }
        }
    }

    /** Back up the projects and hand the archive to the host's share sheet. */
    fun backupProjects(share: (String) -> Unit) {
        scope.launch {
            backend.projects.backupProjects()?.let(share)
            show(SettingsToast.BackupReady)
        }
    }

    fun show(value: SettingsToast) {
        toast = value
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2400)
            toast = null
        }
    }
}

@Composable
internal fun rememberSettingsScreenState(
    backend: IdeBackend,
    ads: AdController?,
    scope: CoroutineScope = rememberCoroutineScope(),
): SettingsScreenState = remember(backend, ads, scope) { SettingsScreenState(backend, ads, scope) }
