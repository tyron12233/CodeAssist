package dev.ide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.update_ready_later
import dev.ide.ui.generated.resources.update_ready_message
import dev.ide.ui.generated.resources.update_ready_restart
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * "An update is ready" bar along the bottom of the window, snackbar-styled so it reads as a notice and not a
 * dialog: the user can keep working under it. Restart installs (the caller saves first); Later hides it until
 * the next launch, when the still-downloaded update is offered again.
 */
@Composable
fun UpdateReadyBanner(visible: Boolean, onRestart: () -> Unit, onLater: () -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(Ca.radius.md),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(Res.string.update_ready_message),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(onLater) {
                        Text(stringResource(Res.string.update_ready_later), color = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                    TextButton(onRestart) {
                        Text(stringResource(Res.string.update_ready_restart), color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}
