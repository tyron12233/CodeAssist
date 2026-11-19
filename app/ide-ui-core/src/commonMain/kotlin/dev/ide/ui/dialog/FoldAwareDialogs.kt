package dev.ide.ui.dialog

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/**
 * Keeps the dialog this is composed in off a foldable's hinge while the hinge divides the screen: the dialog
 * window moves to one side and narrows to fit it. Called from a dialog's content. A no-op on a host whose
 * dialogs are not separate windows over a folding screen.
 */
@Composable
expect fun DialogFoldEffect()

/**
 * Material's `AlertDialog`, kept off a foldable's hinge. Same parameters, so a call site only swaps its
 * import.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = {
        // The one slot every alert has, so the effect always runs inside the dialog's own window.
        DialogFoldEffect()
        confirmButton()
    },
    modifier = modifier,
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    shape = shape,
    containerColor = containerColor,
    iconContentColor = iconContentColor,
    titleContentColor = titleContentColor,
    textContentColor = textContentColor,
    tonalElevation = tonalElevation,
    properties = properties,
)

/** Material's `BasicAlertDialog`, kept off a foldable's hinge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.material3.BasicAlertDialog(onDismissRequest, modifier, properties) {
    DialogFoldEffect()
    content()
}

/** Compose's `Dialog`, kept off a foldable's hinge. */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
    DialogFoldEffect()
    content()
}
