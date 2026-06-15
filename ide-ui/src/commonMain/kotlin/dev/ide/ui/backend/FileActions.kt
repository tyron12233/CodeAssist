package dev.ide.ui.backend

/**
 * Platform file-system bridges the reusable UI can't express itself — picking files to import (Android
 * SAF / desktop `JFileChooser`) and sharing a file out (Android `FileProvider` chooser / desktop reveal).
 * The host supplies a concrete implementation to [dev.ide.ui.CodeAssistApp]; the byte-copy on import
 * ultimately routes through [IdeBackend.createFile], so the model/VFS layer stays untouched.
 */
interface FileActions {
    /** Whether this host can import external files (shows the Import affordance). */
    val canImport: Boolean

    /**
     * Launch the platform file picker and copy the chosen file(s) into [targetDir] (a project directory).
     * [onImported] is called on the UI thread with the new files' paths so the caller can refresh the tree
     * and open them — empty if the user cancelled or nothing was copied.
     */
    fun importInto(targetDir: String, onImported: (List<String>) -> Unit)

    /** Whether this host can share/export a file out (shows the Share affordance). */
    val canShare: Boolean

    /** Share/export the file at [path] to another app (Android) or reveal it in the file manager (desktop). */
    fun share(path: String)

    /**
     * Open [url] in the platform browser (Android `Intent.ACTION_VIEW` / desktop `Desktop.browse`).
     * Used by the Beta notice's "Submit suggestions" action. Defaults to a no-op so hosts that don't
     * support external links degrade gracefully (the affordance is hidden when [canOpenUrl] is false).
     */
    fun openUrl(url: String) = Unit

    /** Whether this host can open external URLs (shows link affordances like "Submit suggestions"). */
    val canOpenUrl: Boolean get() = false

    /** A no-op bridge for hosts without file integration (the default). */
    object None : FileActions {
        override val canImport: Boolean = false
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) = Unit
        override val canShare: Boolean = false
        override fun share(path: String) = Unit
    }
}
