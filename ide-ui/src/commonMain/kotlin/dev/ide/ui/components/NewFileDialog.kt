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
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.backend.UiSourceRootRole
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.add
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.create
import dev.ide.ui.generated.resources.newfile_add_source_root
import dev.ide.ui.generated.resources.newfile_created_at
import dev.ide.ui.generated.resources.newfile_custom_folder_hint
import dev.ide.ui.generated.resources.newfile_file_name
import dev.ide.ui.generated.resources.newfile_file_name_hint
import dev.ide.ui.generated.resources.newfile_folder_name
import dev.ide.ui.generated.resources.newfile_folder_name_hint
import dev.ide.ui.generated.resources.newfile_help_file
import dev.ide.ui.generated.resources.newfile_help_folder
import dev.ide.ui.generated.resources.newfile_in
import dev.ide.ui.generated.resources.newfile_kind
import dev.ide.ui.generated.resources.newfile_kind_abstract_class
import dev.ide.ui.generated.resources.newfile_kind_android_res
import dev.ide.ui.generated.resources.newfile_kind_annotation
import dev.ide.ui.generated.resources.newfile_kind_assets
import dev.ide.ui.generated.resources.newfile_kind_class
import dev.ide.ui.generated.resources.newfile_kind_data_class
import dev.ide.ui.generated.resources.newfile_kind_enum
import dev.ide.ui.generated.resources.newfile_kind_file
import dev.ide.ui.generated.resources.newfile_kind_interface
import dev.ide.ui.generated.resources.newfile_kind_object
import dev.ide.ui.generated.resources.newfile_mode_directory
import dev.ide.ui.generated.resources.newfile_mode_resource_file
import dev.ide.ui.generated.resources.newfile_name
import dev.ide.ui.generated.resources.newfile_new_folder
import dev.ide.ui.generated.resources.newfile_new_java_class
import dev.ide.ui.generated.resources.newfile_new_kotlin_file
import dev.ide.ui.generated.resources.newfile_new_resource_directory
import dev.ide.ui.generated.resources.newfile_new_resource_title
import dev.ide.ui.generated.resources.newfile_new_set
import dev.ide.ui.generated.resources.newfile_package
import dev.ide.ui.generated.resources.newfile_preset_aidl
import dev.ide.ui.generated.resources.newfile_preset_custom
import dev.ide.ui.generated.resources.newfile_preset_java
import dev.ide.ui.generated.resources.newfile_preset_kotlin
import dev.ide.ui.generated.resources.newfile_preset_resources
import dev.ide.ui.generated.resources.newfile_resdir_name
import dev.ide.ui.generated.resources.newfile_resdir_name_hint
import dev.ide.ui.generated.resources.newfile_reskind_drawable
import dev.ide.ui.generated.resources.newfile_reskind_layout
import dev.ide.ui.generated.resources.newfile_reskind_menu
import dev.ide.ui.generated.resources.newfile_reskind_values
import dev.ide.ui.generated.resources.newfile_reskind_xml
import dev.ide.ui.generated.resources.newfile_res_layout_hint
import dev.ide.ui.generated.resources.newfile_res_name_hint
import dev.ide.ui.generated.resources.newfile_role_sources
import dev.ide.ui.generated.resources.newfile_root_element
import dev.ide.ui.generated.resources.newfile_set_name_hint
import dev.ide.ui.generated.resources.newfile_source_java_hint
import dev.ide.ui.generated.resources.newfile_source_kotlin_hint
import dev.ide.ui.generated.resources.newfile_source_set
import dev.ide.ui.generated.resources.newfile_title
import dev.ide.ui.generated.resources.newfile_to
import dev.ide.ui.generated.resources.newfile_treat_as
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a unified "New…" action creates, and where. [dirLabel] is a short path shown to the user.
 * [packages] is the (possibly compacted) package chain that [dirPath] belongs to; when it has more than
 * one level the dialog shows it as selectable chips so a file can be dropped at a *middle* package
 * (`com`, `com.example`) instead of only the deepest one. [dirPath] is the level preselected.
 */
enum class NewEntryKind { File, Folder }
data class NewEntryRequest(
    val dirPath: String,
    val kind: NewEntryKind,
    val dirLabel: String,
    val packages: List<PackageSegment> = emptyList(),
)

/**
 * The unified New-File / New-Folder dialog — create *anything, anywhere*. Drops from the top (reusing
 * [DropdownOverlay]). One name field: the name may include nested folders (`a/b/Helper.kt`), all created
 * along the way, and for a file the backend scaffolds content from the extension (`.java`/`.kt` → a class
 * stub with the resolved package, `.xml` → a root element, else empty). [onCreate] hands the target dir +
 * the (possibly nested) name + the kind to the host. The last request is retained so the exit animation
 * doesn't flash empty.
 */
@Composable
fun NewEntryDialog(
    request: NewEntryRequest?,
    onDismiss: () -> Unit,
    onCreate: (dirPath: String, name: String, kind: NewEntryKind) -> Unit,
) {
    var shown by remember { mutableStateOf<NewEntryRequest?>(null) }
    if (request != null) shown = request
    DropdownOverlay(visible = request != null, onDismiss = onDismiss, topPadding = 110.dp) {
        shown?.let { NewEntryPanel(it, onDismiss, onCreate) }
    }
}

@Composable
private fun NewEntryPanel(
    req: NewEntryRequest,
    onDismiss: () -> Unit,
    onCreate: (String, String, NewEntryKind) -> Unit,
) {
    val isFolder = req.kind == NewEntryKind.Folder
    var name by remember(req) { mutableStateOf("") }
    // The package level to create in; defaults to the level the action was launched from.
    var targetDir by remember(req) { mutableStateOf(req.dirPath) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) { runCatching { focus.requestFocus() } }

    // Non-empty; allow nested segments split by '/'; reject characters illegal in a path component.
    val trimmed = name.trim().replace('\\', '/').trim('/')
    val illegal = charArrayOf(':', '*', '?', '"', '<', '>', '|')
    val valid = trimmed.isNotEmpty() && trimmed.split('/').all { seg -> seg.isNotEmpty() && seg.none { it in illegal } }

    fun submit() {
        if (!valid) return
        onCreate(targetDir, trimmed, req.kind)
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
        Text(stringResource(if (isFolder) Res.string.newfile_new_folder else Res.string.newfile_title), color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val selPkg = req.packages.firstOrNull { it.dirPath == targetDir }?.packageName
        Text(stringResource(Res.string.newfile_in, selPkg ?: req.dirLabel), color = Ca.colors.textTertiary, style = Ca.type.caption2)
        Spacer12()

        PackageChips(req.packages, targetDir, onSelect = { targetDir = it })

        FieldLabel(stringResource(if (isFolder) Res.string.newfile_folder_name else Res.string.newfile_file_name))
        DialogField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(if (isFolder) Res.string.newfile_folder_name_hint else Res.string.newfile_file_name_hint),
            focusRequester = focus,
            onSubmit = ::submit,
            onCancel = onDismiss,
        )
        Spacer8()
        Text(
            stringResource(if (isFolder) Res.string.newfile_help_folder else Res.string.newfile_help_file),
            color = Ca.colors.textTertiary,
            style = Ca.type.caption2,
        )
        Spacer12()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onDismiss)
            DialogButton(stringResource(Res.string.create), primary = true, enabled = valid, onClick = ::submit)
        }
    }
}

// ---------------------------------------------------------------------------
// New typed source file (Java class / Kotlin file, with a kind selector)
// ---------------------------------------------------------------------------

/** Which language a typed "New …" action scaffolds, and where. [dirLabel] is a short path shown to the user.
 *  [packages] mirrors [NewEntryRequest.packages]: the package chain so the dialog can target a middle level. */
enum class NewSourceLang { Java, Kotlin }
data class NewSourceRequest(
    val dirPath: String,
    val lang: NewSourceLang,
    val dirLabel: String,
    val packages: List<PackageSegment> = emptyList(),
)

/**
 * The package-level chooser shown when a "New …" action is launched from a compacted package node: one chip
 * per level of the chain (`com` · `com.example` · `com.example.compose`), so the file can be created at a
 * middle package, not only the deepest. [selectedDir] is the chip currently active; [onSelect] hands back the
 * chosen level's directory. Renders nothing for a non-package context (a single level or none).
 */
@Composable
private fun PackageChips(packages: List<PackageSegment>, selectedDir: String, onSelect: (String) -> Unit) {
    if (packages.size <= 1) return
    FieldLabel(stringResource(Res.string.newfile_package))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        packages.forEach { seg ->
            SelectChip(seg.packageName, selected = seg.dirPath == selectedDir, onClick = { onSelect(seg.dirPath) })
        }
    }
    Spacer12()
}

/** A kind offered in the typed-source dialog → the backend template it scaffolds. */
private enum class SourceKind(val label: StringResource, val template: UiNewFileTemplate) {
    JClass(Res.string.newfile_kind_class, UiNewFileTemplate.JavaClass),
    JInterface(Res.string.newfile_kind_interface, UiNewFileTemplate.JavaInterface),
    JEnum(Res.string.newfile_kind_enum, UiNewFileTemplate.JavaEnum),
    JAbstract(Res.string.newfile_kind_abstract_class, UiNewFileTemplate.JavaAbstractClass),
    JAnnotation(Res.string.newfile_kind_annotation, UiNewFileTemplate.JavaAnnotation),
    KClass(Res.string.newfile_kind_class, UiNewFileTemplate.KotlinClass),
    KFile(Res.string.newfile_kind_file, UiNewFileTemplate.KotlinFile),
    KInterface(Res.string.newfile_kind_interface, UiNewFileTemplate.KotlinInterface),
    KData(Res.string.newfile_kind_data_class, UiNewFileTemplate.KotlinDataClass),
    KEnum(Res.string.newfile_kind_enum, UiNewFileTemplate.KotlinEnum),
    KObject(Res.string.newfile_kind_object, UiNewFileTemplate.KotlinObject),
}

private fun kindsFor(lang: NewSourceLang): List<SourceKind> = when (lang) {
    NewSourceLang.Java -> listOf(SourceKind.JClass, SourceKind.JInterface, SourceKind.JEnum, SourceKind.JAbstract, SourceKind.JAnnotation)
    NewSourceLang.Kotlin -> listOf(SourceKind.KClass, SourceKind.KFile, SourceKind.KInterface, SourceKind.KData, SourceKind.KEnum, SourceKind.KObject)
}

/**
 * The typed New-Java-class / New-Kotlin-file dialog: a kind selector (Class/Interface/Enum/… for Java;
 * Class/File/Interface/Data class/… for Kotlin) + a bare type-name field (no extension). The backend
 * scaffolds the stub with the package resolved from the target directory and picks the `.java`/`.kt`
 * extension. Hands `(dir, name, template)` to [onCreate].
 */
@Composable
fun NewSourceFileDialog(
    request: NewSourceRequest?,
    onDismiss: () -> Unit,
    onCreate: (dirPath: String, name: String, template: UiNewFileTemplate) -> Unit,
) {
    var shown by remember { mutableStateOf<NewSourceRequest?>(null) }
    if (request != null) shown = request
    DropdownOverlay(visible = request != null, onDismiss = onDismiss, topPadding = 110.dp) {
        shown?.let { NewSourcePanel(it, onDismiss, onCreate) }
    }
}

@Composable
private fun NewSourcePanel(
    req: NewSourceRequest,
    onDismiss: () -> Unit,
    onCreate: (String, String, UiNewFileTemplate) -> Unit,
) {
    val kinds = remember(req) { kindsFor(req.lang) }
    var kind by remember(req) { mutableStateOf(kinds.first()) }
    var name by remember(req) { mutableStateOf("") }
    // The package level to create in; defaults to the level the action was launched from.
    var targetDir by remember(req) { mutableStateOf(req.dirPath) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) { runCatching { focus.requestFocus() } }

    // A bare type name: a letter, then letters/digits/underscore (no extension, no path separators).
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.first().isLetter() && trimmed.all { it.isLetterOrDigit() || it == '_' }

    fun submit() {
        if (!valid) return
        onCreate(targetDir, trimmed, kind.template)
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
        Text(
            stringResource(if (req.lang == NewSourceLang.Java) Res.string.newfile_new_java_class else Res.string.newfile_new_kotlin_file),
            color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        val selPkg = req.packages.firstOrNull { it.dirPath == targetDir }?.packageName
        Text(stringResource(Res.string.newfile_in, selPkg ?: req.dirLabel), color = Ca.colors.textTertiary, style = Ca.type.caption2)
        Spacer12()

        PackageChips(req.packages, targetDir, onSelect = { targetDir = it })

        FieldLabel(stringResource(Res.string.newfile_kind))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k -> SelectChip(stringResource(k.label), selected = k == kind, onClick = { kind = k }) }
        }
        Spacer12()

        FieldLabel(stringResource(Res.string.newfile_name))
        DialogField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(if (req.lang == NewSourceLang.Java) Res.string.newfile_source_java_hint else Res.string.newfile_source_kotlin_hint),
            focusRequester = focus,
            onSubmit = ::submit,
            onCancel = onDismiss,
        )
        Spacer12()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onDismiss)
            DialogButton(stringResource(Res.string.create), primary = true, enabled = valid, onClick = ::submit)
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
private enum class XmlResKind(val label: StringResource, val folder: String) {
    Layout(Res.string.newfile_reskind_layout, "layout"),
    Values(Res.string.newfile_reskind_values, "values"),
    Drawable(Res.string.newfile_reskind_drawable, "drawable"),
    Menu(Res.string.newfile_reskind_menu, "menu"),
    Xml(Res.string.newfile_reskind_xml, "xml"),
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

private enum class XmlMode(val label: StringResource) { File(Res.string.newfile_mode_resource_file), Directory(Res.string.newfile_mode_directory) }

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
        val title = if (mode == XmlMode.Directory) stringResource(Res.string.newfile_new_resource_directory)
            else stringResource(Res.string.newfile_new_resource_title, stringResource(kind.label).lowercase())
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer8()

        // File vs Directory.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XmlMode.entries.forEach { m -> SelectChip(stringResource(m.label), selected = m == mode, onClick = { mode = m }) }
        }
        Spacer12()

        if (mode == XmlMode.File) {
            // Kind selector only when the target is the res root (a specific folder fixes the kind).
            if (fixedKind == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XmlResKind.entries.forEach { k -> SelectChip(stringResource(k.label), selected = k == kind, onClick = { kind = k }) }
                }
                Spacer12()
            }
            FieldLabel(stringResource(Res.string.newfile_name))
            DialogField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(if (kind == XmlResKind.Layout) Res.string.newfile_res_layout_hint else Res.string.newfile_res_name_hint),
                focusRequester = focus,
                onSubmit = ::submit,
                onCancel = onDismiss,
            )
            if (kind == XmlResKind.Layout) {
                Spacer12()
                FieldLabel(stringResource(Res.string.newfile_root_element))
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LAYOUT_ROOTS.forEach { r -> SelectChip(r, selected = r == root, onClick = { root = r }) }
                }
            }
        } else {
            FieldLabel(stringResource(Res.string.newfile_resdir_name))
            DialogField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(Res.string.newfile_resdir_name_hint),
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
            DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onDismiss)
            DialogButton(stringResource(Res.string.create), primary = true, enabled = valid, onClick = ::submit)
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

// ---------------------------------------------------------------------------
// Add source root / source set (Java · Kotlin · resources · res · assets · custom)
// ---------------------------------------------------------------------------

/** A request to add a source root to [moduleName]; [sourceSets] are its existing set names (for the picker). */
data class AddSourceRootRequest(val moduleName: String, val sourceSets: List<String>)

/** A preset source-root kind → the leaf folder it creates and the role it carries. [Custom] is free-form. */
private enum class RootPreset(val label: StringResource, val dirName: String, val role: UiSourceRootRole) {
    Java(Res.string.newfile_preset_java, "java", UiSourceRootRole.Source),
    Kotlin(Res.string.newfile_preset_kotlin, "kotlin", UiSourceRootRole.Source),
    Resources(Res.string.newfile_preset_resources, "resources", UiSourceRootRole.Resource),
    AndroidRes(Res.string.newfile_kind_android_res, "res", UiSourceRootRole.AndroidRes),
    Assets(Res.string.newfile_kind_assets, "assets", UiSourceRootRole.Assets),
    Aidl(Res.string.newfile_preset_aidl, "aidl", UiSourceRootRole.Aidl),
    Custom(Res.string.newfile_preset_custom, "", UiSourceRootRole.Source),
}

private fun roleLabel(role: UiSourceRootRole): StringResource = when (role) {
    UiSourceRootRole.Source -> Res.string.newfile_role_sources
    UiSourceRootRole.Resource -> Res.string.newfile_preset_resources
    UiSourceRootRole.AndroidRes -> Res.string.newfile_kind_android_res
    UiSourceRootRole.Assets -> Res.string.newfile_kind_assets
    UiSourceRootRole.Aidl -> Res.string.newfile_preset_aidl
}

/**
 * The Add-Source-Root dialog: pick a target source set (an existing one or a new name) and a kind. A preset
 * fixes the folder name + role (Java→`java`/sources, Resources→`resources`/resources, …); **Custom** reveals
 * a free folder-name field + an explicit role picker. Hands `(module, set, dirName, role)` to [onAdd];
 * [dev.ide.ui.AppState.addSourceRoot] places it at `src/<set>/<dirName>` and refreshes the tree.
 */
@Composable
fun AddSourceRootDialog(
    request: AddSourceRootRequest?,
    onDismiss: () -> Unit,
    onAdd: (moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole) -> Unit,
) {
    var shown by remember { mutableStateOf<AddSourceRootRequest?>(null) }
    if (request != null) shown = request
    DropdownOverlay(visible = request != null, onDismiss = onDismiss, topPadding = 110.dp) {
        shown?.let { AddSourceRootPanel(it, onDismiss, onAdd) }
    }
}

@Composable
private fun AddSourceRootPanel(
    req: AddSourceRootRequest,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, UiSourceRootRole) -> Unit,
) {
    var preset by remember(req) { mutableStateOf(RootPreset.Java) }
    var customRole by remember(req) { mutableStateOf(UiSourceRootRole.Source) }
    var customName by remember(req) { mutableStateOf("") }
    var newSet by remember(req) { mutableStateOf(req.sourceSets.isEmpty()) }
    var setName by remember(req) { mutableStateOf(req.sourceSets.firstOrNull() ?: "main") }
    val focus = remember { FocusRequester() }

    val isCustom = preset == RootPreset.Custom
    val dirName = (if (isCustom) customName else preset.dirName).trim().trim('/')
    val role = if (isCustom) customRole else preset.role
    val effectiveSet = setName.trim()
    val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    val nameOk = dirName.isNotEmpty() && dirName.none { it in illegal }
    val setOk = effectiveSet.isNotEmpty() && effectiveSet.none { it in illegal }
    val valid = nameOk && setOk

    fun submit() {
        if (!valid) return
        onAdd(req.moduleName, effectiveSet, dirName, role)
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
        Text(stringResource(Res.string.newfile_add_source_root), color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.newfile_to, req.moduleName), color = Ca.colors.textTertiary, style = Ca.type.caption2)
        Spacer12()

        // Source-set selector: existing sets + a "New set…" toggle that reveals a name field.
        FieldLabel(stringResource(Res.string.newfile_source_set))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            req.sourceSets.forEach { s ->
                SelectChip(s, selected = !newSet && s == setName, onClick = { newSet = false; setName = s })
            }
            SelectChip(stringResource(Res.string.newfile_new_set), selected = newSet, onClick = { newSet = true; setName = "" })
        }
        if (newSet) {
            Spacer8()
            DialogField(
                value = setName,
                onValueChange = { setName = it },
                placeholder = stringResource(Res.string.newfile_set_name_hint),
                onSubmit = ::submit,
                onCancel = onDismiss,
            )
        }
        Spacer12()

        // Kind presets.
        FieldLabel(stringResource(Res.string.newfile_kind))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RootPreset.entries.forEach { p -> SelectChip(stringResource(p.label), selected = p == preset, onClick = { preset = p }) }
        }

        if (isCustom) {
            Spacer12()
            FieldLabel(stringResource(Res.string.newfile_folder_name))
            DialogField(
                value = customName,
                onValueChange = { customName = it },
                placeholder = stringResource(Res.string.newfile_custom_folder_hint),
                focusRequester = focus,
                onSubmit = ::submit,
                onCancel = onDismiss,
            )
            Spacer8()
            FieldLabel(stringResource(Res.string.newfile_treat_as))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiSourceRootRole.entries.forEach { r ->
                    SelectChip(stringResource(roleLabel(r)), selected = r == customRole, onClick = { customRole = r })
                }
            }
        }
        Spacer8()
        Text(
            stringResource(Res.string.newfile_created_at, "src/$effectiveSet/${if (isCustom) dirName.ifEmpty { "…" } else preset.dirName}"),
            color = Ca.colors.textTertiary,
            style = Ca.type.caption2,
        )
        Spacer12()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onDismiss)
            DialogButton(stringResource(Res.string.add), primary = true, enabled = valid, onClick = ::submit)
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun DialogField(
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
internal fun DialogButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
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

@Composable internal fun Spacer8() = Spacer(Modifier.height(8.dp))
@Composable internal fun Spacer12() = Spacer(Modifier.height(12.dp))
