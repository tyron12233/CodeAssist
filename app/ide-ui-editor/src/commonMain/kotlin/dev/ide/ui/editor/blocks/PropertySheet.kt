package dev.ide.ui.editor.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.block_property_none
import dev.ide.ui.generated.resources.block_property_optional
import dev.ide.ui.generated.resources.block_property_overloads
import dev.ide.ui.generated.resources.block_property_title
import dev.ide.ui.generated.resources.block_scope_loading
import dev.ide.ui.generated.resources.block_scope_none
import dev.ide.ui.generated.resources.block_scope_title
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * What a call can still take: its optional parameters (each with its type), picked one at a time to become a
 * hole on the block, and, when the callee is overloaded, its signatures to switch between.
 */
@Composable
internal fun PropertySheet(
    sig: CallSig,
    optional: List<MissingParam>,
    kotlin: Boolean,
    onPick: (MissingParam) -> Unit,
    onOverload: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val syntax = Ide.colors.syntax
    val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(Ca.radius.sheet)).background(scheme.surfaceContainerHigh)
                .padding(20.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(Res.string.block_property_title), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, modifier = Modifier.padding(bottom = 8.dp))
            SectionTitle(stringResource(Res.string.block_property_optional))
            if (optional.isEmpty()) {
                Text(stringResource(Res.string.block_property_none), style = MaterialTheme.typography.bodySmall, color = scheme.outline, modifier = Modifier.padding(vertical = 6.dp))
            }
            for (m in optional) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).clickable { onPick(m) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(CaIcons.plus, null, Modifier.size(14.dp), tint = scheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(m.param.name ?: "", style = Ide.type.code, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        remember(m.param.type, syntax) { dev.ide.ui.editor.highlight(m.param.type, lang, syntax) },
                        style = Ide.type.codeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
            if (sig.overloads.size > 1) {
                SectionTitle(stringResource(Res.string.block_property_overloads))
                sig.overloads.forEachIndexed { i, params ->
                    val label = params.joinToString(", ", "(", ")") { p -> listOfNotNull(p.name, p.type).joinToString(": ").let { if (p.optional) "$it = …" else it } }
                    val on = i == sig.chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm))
                            .background(if (on) scheme.secondaryContainer else Color.Transparent)
                            .clickable { onOverload(i) }.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            remember(label, syntax) { dev.ide.ui.editor.highlight(label, lang, syntax) },
                            style = Ide.type.codeSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (on) Icon(CaIcons.check, null, Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

/** The quick list behind a scoped body's `+`: what the lambda's [receiver] adds; [functions] is null while loading. */
@Composable
internal fun ScopePicker(receiver: String, functions: List<PaletteTemplate>?, kotlin: Boolean, onPick: (PaletteTemplate) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val syntax = Ide.colors.syntax
    val lang = remember(kotlin) { dev.ide.ui.editor.languageFor(if (kotlin) "a.kt" else "a.java") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(Ca.radius.sheet)).background(scheme.surfaceContainerHigh)
                .padding(20.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(Res.string.block_scope_title), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            Text(receiver, style = Ide.type.codeSmall, color = scheme.outline, modifier = Modifier.padding(bottom = 8.dp))
            when {
                functions == null -> Text(stringResource(Res.string.block_scope_loading), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                functions.isEmpty() -> Text(stringResource(Res.string.block_scope_none), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                else -> for (f in functions) {
                    val shown = withoutBody(f.text).replace("\n", " ").replace(Regex("\\s+"), " ")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).clickable { onPick(f) }.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(CaIcons.plus, null, Modifier.size(14.dp), tint = scheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(remember(shown, syntax) { dev.ide.ui.editor.highlight(shown, lang, syntax) }, style = Ide.type.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}
