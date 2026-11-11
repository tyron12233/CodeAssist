package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.copy
import dev.ide.ui.generated.resources.false_error_building
import dev.ide.ui.generated.resources.false_error_failed
import dev.ide.ui.generated.resources.false_error_intro
import dev.ide.ui.generated.resources.false_error_title
import dev.ide.ui.generated.resources.share
import org.jetbrains.compose.resources.stringResource

/** A false-error report as the dialog holds it: still being built, built, or failed. */
internal sealed interface FalseErrorReportState {
    data object Building : FalseErrorReportState
    data class Ready(val report: String) : FalseErrorReportState
    data object Failed : FalseErrorReportState
}

/**
 * Shows a false-error report in full before anything leaves the device: the user reads exactly what will be
 * shared (it includes the flagged file), then shares it as a file ([onShare], null where the host cannot),
 * copies it, or closes.
 */
@Composable
internal fun FalseErrorReportDialog(
    state: FalseErrorReportState,
    onShare: ((String) -> Unit)?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val report = (state as? FalseErrorReportState.Ready)?.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.false_error_title)) },
        text = {
            Column {
                when (state) {
                    FalseErrorReportState.Building -> {
                        Text(stringResource(Res.string.false_error_building), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                    FalseErrorReportState.Failed ->
                        Text(stringResource(Res.string.false_error_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is FalseErrorReportState.Ready -> {
                        Text(stringResource(Res.string.false_error_intro), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            SelectionContainer {
                                Text(state.report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (report != null) {
                if (onShare != null) TextButton(onClick = { onShare(report) }) { Text(stringResource(Res.string.share)) }
                else TextButton(onClick = { onCopy(report) }) { Text(stringResource(Res.string.copy)) }
            }
        },
        dismissButton = {
            if (report != null && onShare != null) TextButton(onClick = { onCopy(report) }) { Text(stringResource(Res.string.copy)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )
}
