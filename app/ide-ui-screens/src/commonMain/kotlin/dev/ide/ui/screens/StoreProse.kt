package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import dev.ide.ui.markdown.Markdown

/**
 * Publisher-written prose: a store listing's description, its README, and the preview the submit form
 * shows while the description is still being typed.
 *
 * The description column has been Markdown since the store's first migration (`store_items.description`
 * is documented as "markdown, rendered by the shared renderer"), but the client rendered it as one flat
 * string, so a description written as a list arrived as a paragraph with hyphens in it. Routing all three
 * surfaces through here keeps the form's preview from drifting from the listing it previews.
 *
 * The renderer is the shared one rather than a store-local dialect. The heading scale is flatter than its
 * default: this text sits inside a page that already has a title, a hero and tabs, so an `#` in someone's
 * description must not come out larger than the name of the project it describes.
 */
@Composable
internal fun ListingProse(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Markdown(
        text,
        modifier = modifier,
        paragraphStyle = style,
        color = color,
        headingStyle = { level -> listingHeading(level) },
    )
}

@Composable
private fun listingHeading(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
}
