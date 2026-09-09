package dev.ide.ui.components

import androidx.compose.runtime.Composable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_downloading_pct
import dev.ide.ui.generated.resources.store_install
import dev.ide.ui.generated.resources.store_installing
import dev.ide.ui.generated.resources.store_open
import dev.ide.ui.generated.resources.store_retry
import dev.ide.ui.generated.resources.store_unpacking
import dev.ide.ui.generated.resources.store_use
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiStoreItem
import org.jetbrains.compose.resources.stringResource

/**
 * The label on an item's action button, which is the only place an install reports itself on a shelf.
 *
 * A download is the one action here that takes long enough to need saying so. The percentage is shown
 * rather than a spinner because the size is known up front, and a stalled transfer should look stalled.
 */
@Composable
internal fun installActionLabel(item: UiStoreItem, progress: UiInstallProgress?): String = when {
    progress?.state == UiInstallState.DOWNLOADING ->
        if (progress.fraction > 0f) {
            stringResource(Res.string.store_downloading_pct, (progress.fraction * 100).toInt())
        } else {
            stringResource(Res.string.store_installing)
        }
    progress?.state == UiInstallState.IMPORTING -> stringResource(Res.string.store_unpacking)
    progress?.state == UiInstallState.INSTALLED -> stringResource(Res.string.store_open)
    progress?.state == UiInstallState.FAILED -> stringResource(Res.string.store_retry)
    !item.available -> stringResource(Res.string.store_open)
    // A template is already on the device: it is created locally, never downloaded.
    item.templateId != null -> stringResource(Res.string.store_use)
    else -> stringResource(Res.string.store_install)
}

/**
 * Whether tapping again should be ignored.
 *
 * A second tap mid-download would start a second download of the same archive, so the button goes inert
 * for the duration rather than merely looking busy.
 */
internal val UiInstallProgress?.inFlight: Boolean
    get() = this != null &&
        (state == UiInstallState.DOWNLOADING || state == UiInstallState.IMPORTING)
