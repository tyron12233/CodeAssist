package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca

/** A small on-brand pill toggle (matches the module-settings switch), shared by the settings rows. */
@Composable
fun PillToggle(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(Ca.radius.pill),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(20.dp)
                .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(Ca.radius.pill))
        )
    }
}
