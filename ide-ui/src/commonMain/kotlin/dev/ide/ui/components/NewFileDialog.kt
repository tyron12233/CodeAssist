@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.theme.Ca

/** Where a new file will be created: the owning source root, a starting package, and the package levels
 *  offered as quick-pick chips (for a compacted package, one chip per level — e.g. `com`, `com.tyron`). */
data class NewFileTarget(
    val sourceRootPath: String,
    val initialPackage: String,
    val packageChips: List<String>,
)

/** Build a [NewFileTarget] from a tree node, or null if it isn't a Java/Kotlin new-class context. */
fun newFileTargetOf(node: TreeNode): NewFileTarget? {
    val root = node.sourceRootPath ?: return null
    val chips = node.packageSegments.map { it.packageName }
    return NewFileTarget(root, chips.lastOrNull() ?: "", chips)
}

private enum class NewFileLang(val label: String, val ext: String) { Java("Java", "java"), Kotlin("Kotlin", "kt") }

private enum class NewFileKind(val label: String, val inJava: Boolean, val inKotlin: Boolean) {
    Class("Class", inJava = true, inKotlin = true),
    Interface("Interface", inJava = true, inKotlin = true),
    Enum("Enum", inJava = true, inKotlin = true),
    Record("Record", inJava = true, inKotlin = false),
    Object("Object", inJava = false, inKotlin = true),
    DataClass("Data class", inJava = false, inKotlin = true),
}

/**
 * The New-Class dialog: drops from the top (reusing [DropdownOverlay]). Picks a type kind, names it, and
 * chooses/types the package — the chips target an intermediate level of a compacted package (e.g.
 * `com.tyron` inside `com.tyron.codeassist`). On create it computes the target directory under the
 * source root and emits a Java stub via [onCreate]. The last target is retained so the exit animation
 * doesn't flash empty.
 */
@Composable
fun NewFileDialog(
    visible: Boolean,
    target: NewFileTarget?,
    onDismiss: () -> Unit,
    onCreate: (dirPath: String, fileName: String, content: String) -> Unit,
) {
    var shown by remember { mutableStateOf<NewFileTarget?>(null) }
    if (target != null) shown = target
    DropdownOverlay(visible = visible, onDismiss = onDismiss, topPadding = 110.dp) {
        shown?.let { NewFilePanel(it, onDismiss, onCreate) }
    }
}

@Composable
private fun NewFilePanel(
    target: NewFileTarget,
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    var lang by remember { mutableStateOf(NewFileLang.Java) }
    var kind by remember { mutableStateOf(NewFileKind.Class) }
    var name by remember(target) { mutableStateOf("") }
    var pkg by remember(target) { mutableStateOf(target.initialPackage) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) { runCatching { focus.requestFocus() } }

    val kinds = NewFileKind.entries.filter { if (lang == NewFileLang.Kotlin) it.inKotlin else it.inJava }
    val valid = name.isNotEmpty() && name.first().isJavaIdentifierStartCompat() &&
        name.all { it.isJavaIdentifierPartCompat() }

    fun submit() {
        if (!valid) return
        val p = pkg.trim().trim('.')
        val dir = if (p.isEmpty()) target.sourceRootPath
        else target.sourceRootPath.trimEnd('/') + "/" + p.replace('.', '/')
        val content = if (lang == NewFileLang.Kotlin) kotlinStub(p, kind, name) else javaStub(p, kind, name)
        onCreate(dir, "$name.${lang.ext}", content)
        onDismiss()
    }

    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        Text("New ${lang.label} file", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer8()

        // language selector (Java / Kotlin) — switching resets the kind so it stays valid for the language.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NewFileLang.entries.forEach { l ->
                SelectChip(l.label, selected = l == lang, onClick = { lang = l; kind = NewFileKind.Class })
            }
        }
        Spacer8()

        // kind selector (filtered to the kinds the chosen language supports)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k ->
                SelectChip(k.label, selected = k == kind, onClick = { kind = k })
            }
        }
        Spacer12()

        FieldLabel("Name")
        DialogField(
            value = name,
            onValueChange = { name = it },
            placeholder = "MyClass",
            focusRequester = focus,
            onSubmit = ::submit,
            onCancel = onDismiss,
        )
        Spacer12()

        FieldLabel("Package")
        DialogField(
            value = pkg,
            onValueChange = { pkg = it },
            placeholder = "(default package)",
            onSubmit = ::submit,
            onCancel = onDismiss,
        )
        if (target.packageChips.size > 1) {
            Spacer8()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                target.packageChips.forEach { p -> SelectChip(p, selected = p == pkg.trim(), onClick = { pkg = p }) }
            }
        }
        Spacer12()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton("Cancel", primary = false, enabled = true, onClick = onDismiss)
            DialogButton("Create", primary = true, enabled = valid, onClick = ::submit)
        }
    }
}

// ---------------------------------------------------------------------------
// New XML resource (Android layouts / values / drawables / menus)
// ---------------------------------------------------------------------------

/** Where a new XML resource is created: an Android `res/` folder, plus its folder name (`layout`, `values`,
 *  …) or `res` when the target is the res root (then the dialog routes into the chosen kind's subfolder). */
data class NewXmlTarget(val resDir: String, val folderName: String)

/** Build a [NewXmlTarget] from a tree node, or null if it isn't an Android res new-file context. */
fun xmlTargetOf(node: TreeNode): NewXmlTarget? {
    val dir = node.resDirPath ?: return null
    return NewXmlTarget(dir, node.name)
}

/** The kinds of XML resource the dialog can scaffold; [folder] is the `res/<folder>/` it lives in. */
private enum class XmlResKind(val label: String, val folder: String) {
    Layout("Layout", "layout"),
    Values("Values", "values"),
    Drawable("Drawable", "drawable"),
    Menu("Menu", "menu"),
    Xml("XML", "xml"),
}

/** Map a res folder name (qualifiers stripped, e.g. `layout-land` → `layout`) to its kind, or null. */
private fun kindFromFolder(folderName: String): XmlResKind? {
    val base = folderName.substringBefore('-')
    return XmlResKind.entries.firstOrNull { it.folder == base }
}

private val LAYOUT_ROOTS = listOf("LinearLayout", "FrameLayout", "ConstraintLayout", "RelativeLayout", "ScrollView")

/**
 * The New-XML dialog: name the resource, and — for a layout — pick the root element. Routes the file into
 * the right `res/<kind>/` folder (the chosen kind when launched from the res root, else the folder hovered)
 * and writes a starter template via [onCreate], reusing the same `(dir, fileName, content)` seam as the
 * Java dialog (so [dev.ide.ui.AppState.createFile] refreshes the tree + opens it).
 */
/** The standard Android `res/` resource directories, offered as quick-pick chips in Directory mode. */
private val STANDARD_RES_DIRS = listOf(
    "layout", "values", "drawable", "mipmap", "menu", "anim", "animator", "color",
    "raw", "xml", "font", "navigation", "interpolator", "transition",
)

@Composable
fun NewXmlFileDialog(
    visible: Boolean,
    target: NewXmlTarget?,
    onDismiss: () -> Unit,
    onCreate: (dirPath: String, fileName: String, content: String) -> Unit,
    onCreateDir: (parentPath: String, name: String) -> Unit = { _, _ -> },
) {
    var shown by remember { mutableStateOf<NewXmlTarget?>(null) }
    if (target != null) shown = target
    DropdownOverlay(visible = visible, onDismiss = onDismiss, topPadding = 110.dp) {
        shown?.let { NewXmlPanel(it, onDismiss, onCreate, onCreateDir) }
    }
}

private enum class XmlMode(val label: String) { File("Resource file"), Directory("Directory") }

@Composable
private fun NewXmlPanel(
    target: NewXmlTarget,
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
    onCreateDir: (String, String) -> Unit,
) {
    val fixedKind = kindFromFolder(target.folderName)        // null when launched from the `res` root
    var mode by remember(target) { mutableStateOf(XmlMode.File) }
    var kind by remember(target) { mutableStateOf(fixedKind ?: XmlResKind.Layout) }
    var root by remember(target) { mutableStateOf(LAYOUT_ROOTS.first()) }
    var name by remember(target) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) { runCatching { focus.requestFocus() } }

    // Resource file/dir names: lowercase letter/digit/underscore, must start with a letter (Directory mode
    // also accepts a qualifier after a hyphen, e.g. `values-night`).
    val nameOk = name.isNotEmpty() && name.first() in 'a'..'z'
    val valid = when (mode) {
        XmlMode.File -> nameOk && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        XmlMode.Directory -> nameOk && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }

    fun submit() {
        if (!valid) return
        when (mode) {
            XmlMode.File -> {
                val dir = if (fixedKind == null) target.resDir.trimEnd('/') + "/" + kind.folder else target.resDir
                onCreate(dir, "$name.xml", xmlStub(kind, root))
            }
            // Standard res dirs are created under the res root; from a subfolder, under that folder.
            XmlMode.Directory -> onCreateDir(target.resDir, name)
        }
        onDismiss()
    }

    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        val title = if (mode == XmlMode.Directory) "New resource directory" else "New ${kind.label.lowercase()} resource"
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer8()

        // File vs Directory.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XmlMode.entries.forEach { m -> SelectChip(m.label, selected = m == mode, onClick = { mode = m }) }
        }
        Spacer12()

        if (mode == XmlMode.File) {
            // Kind selector only when the target is the res root (a specific folder fixes the kind).
            if (fixedKind == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XmlResKind.entries.forEach { k -> SelectChip(k.label, selected = k == kind, onClick = { kind = k }) }
                }
                Spacer12()
            }
            FieldLabel("Name")
            DialogField(
                value = name,
                onValueChange = { name = it },
                placeholder = if (kind == XmlResKind.Layout) "activity_main" else "my_resource",
                focusRequester = focus,
                onSubmit = ::submit,
                onCancel = onDismiss,
            )
            if (kind == XmlResKind.Layout) {
                Spacer12()
                FieldLabel("Root element")
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LAYOUT_ROOTS.forEach { r -> SelectChip(r, selected = r == root, onClick = { root = r }) }
                }
            }
        } else {
            FieldLabel("Directory name")
            DialogField(
                value = name,
                onValueChange = { name = it },
                placeholder = "drawable-night",
                focusRequester = focus,
                onSubmit = ::submit,
                onCancel = onDismiss,
            )
            Spacer8()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                STANDARD_RES_DIRS.forEach { d -> SelectChip(d, selected = d == name, onClick = { name = d }) }
            }
        }
        Spacer12()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton("Cancel", primary = false, enabled = true, onClick = onDismiss)
            DialogButton("Create", primary = true, enabled = valid, onClick = ::submit)
        }
    }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private fun xmlStub(kind: XmlResKind, root: String): String = when (kind) {
    XmlResKind.Layout ->
        """<?xml version="1.0" encoding="utf-8"?>
<$root xmlns:android="$ANDROID_NS"
    android:layout_width="match_parent"
    android:layout_height="match_parent"${if (root == "LinearLayout") "\n    android:orientation=\"vertical\"" else ""}>

</$root>
"""
    XmlResKind.Values ->
        """<?xml version="1.0" encoding="utf-8"?>
<resources>

</resources>
"""
    XmlResKind.Drawable ->
        """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="$ANDROID_NS"
    android:shape="rectangle">

</shape>
"""
    XmlResKind.Menu ->
        """<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="$ANDROID_NS">

</menu>
"""
    XmlResKind.Xml ->
        """<?xml version="1.0" encoding="utf-8"?>

"""
}

private fun javaStub(pkg: String, kind: NewFileKind, name: String): String {
    val header = if (pkg.isEmpty()) "" else "package $pkg;\n\n"
    val body = when (kind) {
        NewFileKind.Interface -> "public interface $name {\n}\n"
        NewFileKind.Enum -> "public enum $name {\n}\n"
        NewFileKind.Record -> "public record $name() {\n}\n"
        else -> "public class $name {\n}\n" // Class (+ any non-Java kind, unreachable from the Java toggle)
    }
    return header + body
}

private fun kotlinStub(pkg: String, kind: NewFileKind, name: String): String {
    val header = if (pkg.isEmpty()) "" else "package $pkg\n\n"
    val body = when (kind) {
        NewFileKind.Interface -> "interface $name {\n}\n"
        NewFileKind.Object -> "object $name {\n}\n"
        NewFileKind.DataClass -> "data class $name(val value: String)\n"
        NewFileKind.Enum -> "enum class $name {\n}\n"
        else -> "class $name {\n}\n" // Class (+ any non-Kotlin kind, unreachable from the Kotlin toggle)
    }
    return header + body
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester? = null,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
            cursorBrush = SolidColor(Ca.colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> { onCancel(); true }
                        Key.Enter -> { onSubmit(); true }
                        else -> false
                    }
                },
        )
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun DialogButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fill = when {
        primary && enabled -> Ca.colors.accent
        primary -> Ca.colors.accent.copy(alpha = 0.4f)
        else -> Ca.colors.surface3
    }
    val fg = if (primary) Ca.colors.textOnAccent else Ca.colors.textSecondary
    Box(
        Modifier
            .background(fill, RoundedCornerShape(Ca.radius.control))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = fg, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun Spacer8() = Spacer(Modifier.height(8.dp))
@Composable private fun Spacer12() = Spacer(Modifier.height(12.dp))

// Compose Multiplatform commonMain has no java.lang.Character — minimal Java-identifier checks.
private fun Char.isJavaIdentifierStartCompat(): Boolean = isLetter() || this == '_' || this == '$'
private fun Char.isJavaIdentifierPartCompat(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
