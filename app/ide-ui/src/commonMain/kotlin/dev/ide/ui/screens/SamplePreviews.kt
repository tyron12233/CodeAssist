package dev.ide.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.preview_2048
import dev.ide.ui.generated.resources.preview_memory
import dev.ide.ui.generated.resources.preview_snake
import dev.ide.ui.generated.resources.preview_tic_tac_toe
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Built-in "screenshot" preview images for the sample games — real PNGs bundled as Compose drawables (see the
 * `SamplePreviewImageGen` dev tool that renders them). Keyed by [dev.ide.ui.backend.UiStoreItem.previewKey]
 * (the sample's template id); shown on the Explore sample tiles and the store detail screen so a user can see
 * the game before opening it.
 */
@Composable
internal fun SamplePreview(key: String, modifier: Modifier = Modifier) {
    val drawable = previewDrawable(key) ?: return
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/** Whether a bundled preview image exists for [key]. */
internal fun hasSamplePreview(key: String?): Boolean = key != null && previewDrawable(key) != null

private fun previewDrawable(key: String): DrawableResource? = when (key) {
    "sample-snake" -> Res.drawable.preview_snake
    "sample-tictactoe" -> Res.drawable.preview_tic_tac_toe
    "sample-memory" -> Res.drawable.preview_memory
    "sample-2048" -> Res.drawable.preview_2048
    else -> null
}
