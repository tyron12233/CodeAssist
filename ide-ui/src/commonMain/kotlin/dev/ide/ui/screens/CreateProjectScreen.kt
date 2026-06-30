@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.choose_start_point
import dev.ide.ui.generated.resources.create_project
import dev.ide.ui.generated.resources.creating
import dev.ide.ui.generated.resources.new_project
import dev.ide.ui.generated.resources.no_templates_available
import dev.ide.ui.generated.resources.package_name
import dev.ide.ui.generated.resources.project_name
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.resolveTint
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The Create-Project flow: a template gallery (cards grouped by category, from
 * [IdeBackend.projectTemplates]) followed by a configure step whose form is driven entirely by the
 * chosen template's [UiTemplateParam]s — so a new template contributes its own fields with no UI change.
 * On success the backend swaps the active project and [onCreated] fires; [onCancel] backs out.
 */
@Composable
fun CreateProjectScreen(
    backend: IdeBackend,
    onCancel: () -> Unit,
    onCreated: () -> Unit,
    /** Pre-select a template (e.g. opened from a Projects Store item) and jump straight to its configure step. */
    initialTemplateId: String? = null,
) {
    val templates = remember { backend.projects.projectTemplates() }
    var selected by remember(initialTemplateId) {
        mutableStateOf(initialTemplateId?.let { id -> templates.firstOrNull { it.id == id } })
    }
    Box(Modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            val sel = selected
            if (sel == null) {
                Gallery(
                    templates = templates,
                    onBack = onCancel,
                    onPick = { selected = it },
                )
            } else {
                Configure(
                    backend = backend,
                    template = sel,
                    onBack = { selected = null },
                    onCreated = onCreated,
                )
            }
        }
    }
}

// ---- step 1: gallery ----

@Composable
private fun ColumnScope.Gallery(templates: List<UiProjectTemplate>, onBack: () -> Unit, onPick: (UiProjectTemplate) -> Unit) {
    Header(title = stringResource(Res.string.new_project), subtitle = stringResource(Res.string.choose_start_point), onBack = onBack)
    Spacer(Modifier.height(20.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (templates.isEmpty()) {
            Text(stringResource(Res.string.no_templates_available), color = Ca.colors.textSecondary, style = Ca.type.subhead)
        }
        var index = 0
        templates.groupBy { it.category }.forEach { (category, group) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(category.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
                group.forEach { t ->
                    TemplateCard(t, delayMillis = (index++) * 50, onClick = { onPick(t) })
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: UiProjectTemplate, delayMillis: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TemplateGlyph(template.iconId)
        Column(Modifier.weight(1f)) {
            Text(template.displayName, color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(template.description, color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
    }
}

// ---- step 2: configure ----

@Composable
private fun ColumnScope.Configure(
    backend: IdeBackend,
    template: UiProjectTemplate,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    var name by remember(template) { mutableStateOf(defaultName(template)) }
    var pkg by remember(template) { mutableStateOf("") }
    var pkgEdited by remember(template) { mutableStateOf(false) }
    // Auto-derive the package from the name until the user edits it themselves.
    val effectivePkg = if (pkgEdited) pkg else "com.example.${slug(name).replace("-", "")}".ifEmpty { "com.example.app" }

    val paramValues = remember(template) {
        mutableStateMapOf<String, String>().apply {
            template.parameters.forEach { p -> put(p.key, defaultValue(p)) }
        }
    }

    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameValid = name.isNotBlank()
    val pkgValid = effectivePkg.isNotBlank() && effectivePkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    fun create() {
        if (!nameValid || !pkgValid || busy) return
        busy = true; error = null
        val args = HashMap<String, String>().apply {
            put("name", name.trim())
            put("packageName", effectivePkg.trim().trim('.'))
            paramValues.forEach { (k, v) -> put(k, v) }
        }
        scope.launch {
            val result = backend.projects.createProject(template.id, args)
            busy = false
            if (result.success) onCreated() else error = result.message
        }
    }

    Header(title = template.displayName, subtitle = template.description, onBack = onBack)
    Spacer(Modifier.height(20.dp))
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FormField(stringResource(Res.string.project_name), name, "MyProject", onChange = { name = it })
        FormField(stringResource(Res.string.package_name), effectivePkg, "com.example.app", onChange = { pkg = it; pkgEdited = true })
        template.parameters.forEach { p ->
            ParamControl(p, value = paramValues[p.key] ?: defaultValue(p), onChange = { paramValues[p.key] = it })
        }
        error?.let { Text(it, color = Ca.colors.error, style = Ca.type.footnote) }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = stringResource(if (busy) Res.string.creating else Res.string.create_project),
        onClick = ::create,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ParamControl(param: UiTemplateParam, value: String, onChange: (String) -> Unit) {
    when (param) {
        is UiTemplateParam.Text -> FormField(param.label, value, param.placeholder, onChange)
        is UiTemplateParam.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(param.label)
            // FlowRow wraps the chips onto further lines on a narrow (phone) screen instead of squishing
            // them into a single overflowing row.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                param.options.forEach { opt ->
                    Chip(opt.label, selected = value == opt.value, onClick = { onChange(opt.value) })
                }
            }
        }
        is UiTemplateParam.Toggle -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(param.label, color = Ca.colors.textPrimary, style = Ca.type.subhead)
            val on = value.toBooleanStrictOrNull() ?: false
            Box(
                Modifier
                    .background(if (on) Ca.colors.accentSoft else Ca.colors.surface3, RoundedCornerShape(Ca.radius.pill))
                    .clickable { onChange((!on).toString()) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(if (on) "On" else "Off", color = if (on) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- shared bits ----

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(36.dp)
                .pressScale(interaction)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
                .clickable(interaction, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.chevronLeft, "Back", Modifier.size(20.dp), tint = Ca.colors.textSecondary)
        }
        Column {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FormField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) Ca.colors.accentSoft else Ca.colors.surface3
    val fg = if (selected) Ca.colors.accent else Ca.colors.textSecondary
    Box(
        Modifier
            .background(fill, RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = fg, style = Ca.type.footnote, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Render a template's icon id through the shared [TreeIcons] registry (same icons as the file tree). */
@Composable
private fun TemplateGlyph(iconId: String) {
    Box(Modifier.size(44.dp).background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md)), contentAlignment = Alignment.Center) {
        when (val ic = TreeIcons.resolve(iconId)) {
            is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(24.dp), tint = resolveTint(ic.tint))
            is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(24.dp), tint = resolveTint(ic.tint))
            is TreeIcon.Badge -> Text(ic.text, color = ic.color, style = Ca.type.headline, fontWeight = FontWeight.Bold)
        }
    }
}

private fun defaultName(template: UiProjectTemplate): String = when {
    template.id.contains("android-app") -> "My App"
    template.id.contains("android-library") -> "My Library"
    template.id.contains("console") -> "My App"
    template.id.contains("library") -> "My Library"
    else -> "My Project"
}

private fun defaultValue(p: UiTemplateParam): String = when (p) {
    is UiTemplateParam.Text -> p.default
    is UiTemplateParam.Choice -> p.options.getOrNull(p.defaultIndex)?.value ?: p.options.firstOrNull()?.value ?: ""
    is UiTemplateParam.Toggle -> p.default.toString()
}

private fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
