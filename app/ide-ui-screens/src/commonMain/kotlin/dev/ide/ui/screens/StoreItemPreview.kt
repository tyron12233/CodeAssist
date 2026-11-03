package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.CodeMotifColors

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

/**
 * Up to [max] of an item's screenshots, each ready to draw into whatever frame the card gives it.
 *
 * Unlike [rememberItemIcon], the answer does not wait on the fetch: an item that HAS screenshots gets
 * its slots at once, and each slot shows the dark chrome until its image lands. A card that lays itself
 * out differently with and without artwork (the featured hero, the editorial card) would otherwise jump
 * from one layout to the other a moment after it appears.
 *
 * Empty on a host with no [backend], except for bundled preview keys, which need no backend.
 */
@Composable
internal fun rememberItemShots(
    backend: IdeBackend?,
    item: UiStoreItem,
    max: Int = 2,
): List<@Composable (Modifier) -> Unit> = remember(backend, item.previewKey, item.screenshots, max) {
    (listOfNotNull(item.previewKey) + item.screenshots).distinct().mapNotNull { key ->
        val draw: (@Composable (Modifier) -> Unit)? = when {
            hasSamplePreview(key) -> { m -> SamplePreview(key, m) }
            backend != null -> { m -> RemoteShot(backend, key, ContentScale.Crop, m) }
            else -> null
        }
        draw
    }.take(max)
}

/**
 * The item's app icon as a slot that exists from the first frame, for the same reason as
 * [rememberItemShots]. Null when the item has no published icon, so the caller draws its glyph tile.
 */
@Composable
internal fun rememberItemIconSlot(
    backend: IdeBackend?,
    item: UiStoreItem,
): (@Composable (Modifier) -> Unit)? = remember(backend, item.iconPath) {
    val path = item.iconPath
    if (path == null || backend == null) null else { m -> RemoteShot(backend, path, ContentScale.Crop, m) }
}

/** One store media object: resolved to a cached file, then drawn. The chrome stands in until both land. */
@Composable
internal fun RemoteShot(backend: IdeBackend, storagePath: String, scale: ContentScale, modifier: Modifier) {
    val file by produceState<String?>(null, storagePath, backend) {
        value = runCatching { backend.store.screenshotFile(storagePath) }.getOrNull()
    }
    val f = file
    if (f != null) ShotImage(backend, f, scale, modifier) else Box(modifier.background(CodeMotifColors.Chrome))
}
