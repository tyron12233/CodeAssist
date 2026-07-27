package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.library_decompile_java
import dev.ide.ui.generated.resources.library_readonly_decompiled
import dev.ide.ui.generated.resources.library_readonly_source
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A thin banner shown over a read-only LIBRARY tab (a `library://…` decompiled / attached-source view): it
 * states the view is read-only and, unless already showing decompiled Java, offers "Decompile to Java" (runs
 * the full-body Vineflower decompiler on the same class, opening it in a separate read-only tab). Nothing for a
 * normal editable file.
 */
@Composable
internal fun ReadOnlyBanner(state: IdeUiState, active: OpenFile) {
    val kind = active.libraryKind ?: return
    val label = if (kind == "source") stringResource(Res.string.library_readonly_source)
    else stringResource(Res.string.library_readonly_decompiled)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Ca.colors.textTertiary.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ca.colors.textSecondary, style = Ca.type.footnote, modifier = Modifier.weight(1f))
        if (kind != "decompiled_java") {
            Text(
                stringResource(Res.string.library_decompile_java),
                color = Ca.colors.accent,
                style = Ca.type.footnote,
                modifier = Modifier.clickable { state.openLibrary(active.path, forceJava = true) },
            )
        }
    }
}
