package dev.ide.ui.backend

/**
 * The host's app-store update channel: Google Play's in-app updates on Android, nothing anywhere else.
 *
 * The host finds and downloads an update on its own; the shared UI only learns that one is waiting and
 * offers the restart that installs it, because it is the side that knows which buffers are unsaved. Installing
 * takes the process down, so the UI writes every modified file first and then calls [install].
 */
interface AppUpdateHost {
    /** True once an update has been downloaded and needs only a restart. Observable Compose state. */
    val readyToInstall: Boolean

    /** Install the downloaded update. The app restarts; unsaved work must already be on disk. */
    fun install()

    /** Hide the prompt for this launch. The update stays downloaded and is offered again next launch. */
    fun dismiss()

    object None : AppUpdateHost {
        override val readyToInstall: Boolean = false
        override fun install() {}
        override fun dismiss() {}
    }
}
