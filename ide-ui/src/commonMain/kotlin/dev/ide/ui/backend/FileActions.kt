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

    /** Whether this host can pick a single existing file and hand back its path (shows the keystore Import affordance). */
    val canPickFile: Boolean get() = false

    /**
     * Launch the platform file picker for ONE existing file and return its absolute path via [onPicked]
     * (null if cancelled). Unlike [importInto], this does NOT copy the file into the project — the caller
     * reads it directly (e.g. importing a `.jks`/`.keystore` into the keystore registry). Default no-op.
     */
    fun pickFile(onPicked: (String?) -> Unit) = onPicked(null)

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

    /** Whether this host can export/save a copy of a file to a user-chosen location (shows the Export affordance). */
    val canExport: Boolean get() = false

    /**
     * Save a copy of the file at [path] to a user-chosen destination via the platform "Save As" flow —
     * Android `ACTION_CREATE_DOCUMENT` (the system Files app / Drive / Downloads), desktop `JFileChooser`.
     * Unlike [share] (a transient hand-off), this writes a durable copy the user picks the location for —
     * the way to get a built APK/AAB off the device. Default no-op (hidden when [canExport] is false).
     */
    fun exportFile(path: String) = Unit

    /** Whether this host can open a folder in the system file manager (shows the "Open in Files" affordance). */
    val canReveal: Boolean get() = false

    /**
     * Open [path] (a directory) in the system file manager so the user can browse and manage it there.
     * On Android this launches the system Files app at CodeAssist's projects root — surfaced by a
     * DocumentsProvider — so any SAF file manager can browse it and import files (icons/layouts/assets)
     * into a project; on desktop it reveals the folder. Default no-op (the affordance is hidden when
     * [canReveal] is false).
     */
    fun reveal(path: String) = Unit

    /** Whether this host can hand an APK to the system package installer (Android only). */
    val canInstallApk: Boolean get() = false

    /**
     * Prompt the platform package installer for the APK at [path] — on Android the system install
     * confirmation UI (`ACTION_VIEW` of a FileProvider URI). Used when the user taps a built `.apk` in the
     * tree instead of opening it as text. Default no-op (the affordance is hidden when [canInstallApk] is false).
     */
    fun installApk(path: String) = Unit

    /** A no-op bridge for hosts without file integration (the default). */
    object None : FileActions {
        override val canImport: Boolean = false
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) = Unit
        override val canShare: Boolean = false
        override fun share(path: String) = Unit
    }
}
