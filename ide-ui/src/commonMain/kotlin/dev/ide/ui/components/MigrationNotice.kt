package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

/**
 * One-time upgrade notice for users coming from a previous app version. Explains that the rebuilt
 * on-device build system uses its own (incompatible) project configuration, so older projects won't
 * open — and offers a one-tap "Back up projects" to a `.zip` so nothing is lost. Adapts to the
 * platform like onboarding (centered dialog on desktop, bottom sheet on mobile).
 *
 * [onBackup] creates the zip and hands it to the host's share/save sheet; [onDismiss] acknowledges the
 * notice (the host persists the flag so it isn't shown again).
 */
@Composable
fun MigrationNotice(visible: Boolean, onBackup: suspend () -> Unit, onDismiss: () -> Unit) {
    if (isMobilePlatform) {
        BottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.6f) {
            NoticeBody(
                onBackup = onBackup,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    } else {
        CenteredDialog(visible = visible, onDismiss = onDismiss) {
            val shape = RoundedCornerShape(Ca.radius.sheet)
            NoticeBody(
                onBackup = onBackup,
                onDismiss = onDismiss,
                modifier = Modifier
                    .width(460.dp)
                    .background(Ca.colors.glassThick, shape)
                    .border(1.dp, Ca.colors.glassEdge, shape)
                    .padding(28.dp),
            )
        }
    }
}

@Composable
private fun NoticeBody(onBackup: suspend () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).background(Ca.colors.warning.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.box, null, Modifier.size(34.dp), tint = Ca.colors.warning)
        }
        Spacer(Modifier.height(20.dp))
        Text("A new build system", color = Ca.colors.textPrimary, style = Ca.type.title2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "CodeAssist has been rebuilt with a new on-device build system. It uses its own project " +
                "configuration, so projects from a previous version aren't compatible and won't open " +
                "automatically.\n\nBack up your projects to a .zip now — your source files stay safe, and you " +
                "can re-add them to a new project.",
            color = Ca.colors.textSecondary,
            style = Ca.type.subhead,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = if (busy) "Preparing backup…" else "Back up projects",
            onClick = { if (!busy) scope.launch { busy = true; runCatching { onBackup() }; busy = false } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Got it",
            color = Ca.colors.textSecondary,
            style = Ca.type.subhead,
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(12.dp),
        )
    }
}
