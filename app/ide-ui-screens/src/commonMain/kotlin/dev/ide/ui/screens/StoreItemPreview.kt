package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreItem

/**
 * The artwork an Explore card can draw for one item, or null when it has none to draw.
 *
 * Explore's cards fall back to an abstract code motif, which is honest for a scaffold that ships no
 * artwork and wrong for a published project that shipped six screenshots. This is what tells the two
 * apart: a bundled preview drawable first (it needs no round trip), then the first published screenshot,
 * fetched and cached through [dev.ide.ui.backend.StoreService.screenshotFile] the same way the detail
 * gallery fetches the rest.
 *
 * Only the FIRST screenshot, and only for a card that is actually composed: the shelves are lazy, so a
 * feed of two hundred items fetches what the reader scrolls past and no more.
 *
 * Null until the fetch lands, so the card keeps its motif rather than flashing an empty frame, and null
 * forever on a host with no [backend] (the snapshot tests) or when the fetch fails.
 */
@Composable
internal fun rememberItemPreview(
    backend: IdeBackend?,
    item: UiStoreItem,
): (@Composable (Modifier) -> Unit)? {
    // A bundled key wins: it is already on the device, and it is what the bundled samples ship.
    val builtIn = item.previewKey?.takeIf(::hasSamplePreview)
        ?: item.screenshots.firstOrNull()?.takeIf(::hasSamplePreview)
    if (builtIn != null) return { modifier -> SamplePreview(builtIn, modifier) }

    val remote = item.screenshots.firstOrNull { !hasSamplePreview(it) }
    if (remote == null || backend == null) return null
    val path by produceState<String?>(null, remote, backend) {
        value = runCatching { backend.store.screenshotFile(remote) }.getOrNull()
    }
    val file = path ?: return null
    return { modifier -> ShotImage(backend, file, ContentScale.Crop, modifier) }
}

/**
 * The project's own app icon, drawn into whatever tile the caller hands it, or null when it has none.
 *
 * A published listing's icon is an IMAGE in the store's media bucket; `UiStoreItem.iconId` is a glyph key
 * in the app's own registry, which is all a bundled template has. So a card asks for this first and draws
 * its glyph tile when the answer is null, rather than a blank plate.
 *
 * Fetched through the same media cache the screenshots use: it is the same bucket and the same
 * version-scoped key, so an icon is downloaded once per device and never goes stale.
 */
@Composable
internal fun rememberItemIcon(
    backend: IdeBackend?,
    item: UiStoreItem,
): (@Composable (Modifier) -> Unit)? {
    val remote = item.iconPath
    if (remote == null || backend == null) return null
    val path by produceState<String?>(null, remote, backend) {
        value = runCatching { backend.store.screenshotFile(remote) }.getOrNull()
    }
    val file = path ?: return null
    return { modifier -> ShotImage(backend, file, ContentScale.Crop, modifier) }
}
