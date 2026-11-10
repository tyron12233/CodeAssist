package dev.ide.android.layouteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutViewClass
import dev.ide.android.layout.ViewOrigin
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The palette: the views a layout can use, from the host's catalog (framework widgets, the project's library
 * views, its own View subclasses), sorted into the categories a designer looks in. A category lists views by
 * preference, so a Material project's "Button" is the MaterialButton and a plain one gets the framework's; a
 * library view appears only when the project depends on that library.
 */

internal class PaletteCategory(val key: String, val label: StringResource, val entries: List<List<String>>)

private fun e(vararg tags: String) = tags.toList()

private const val M = "com.google.android.material"
private const val X = "androidx"

internal val PALETTE_CATEGORIES = listOf(
    PaletteCategory(
        "common", Res.string.le_palette_common,
        listOf(
            e("TextView"), e("Button"), e("ImageView"), e("$M.textfield.TextInputLayout", "EditText"),
            e("$M.materialswitch.MaterialSwitch", "$M.switchmaterial.SwitchMaterial", "Switch"), e("CheckBox"),
            e("$M.chip.Chip"), e("$X.recyclerview.widget.RecyclerView"), e("$X.constraintlayout.widget.ConstraintLayout"),
            e("LinearLayout"), e("FrameLayout"), e("$M.floatingactionbutton.FloatingActionButton"),
        ),
    ),
    PaletteCategory(
        "text", Res.string.le_palette_text,
        listOf(
            e("TextView"), e("EditText"), e("$M.textfield.TextInputLayout"), e("$M.textfield.TextInputEditText"),
            e("AutoCompleteTextView"), e("MultiAutoCompleteTextView"), e("CheckedTextView"),
        ),
    ),
    PaletteCategory(
        "buttons", Res.string.le_palette_buttons,
        listOf(
            e("Button"), e("ImageButton"), e("$M.floatingactionbutton.FloatingActionButton"), e("CheckBox"),
            e("RadioGroup"), e("RadioButton"), e("ToggleButton"),
            e("$M.materialswitch.MaterialSwitch", "$M.switchmaterial.SwitchMaterial", "Switch"),
            e("$M.chip.ChipGroup"), e("$M.chip.Chip"),
        ),
    ),
    PaletteCategory(
        "widgets", Res.string.le_palette_widgets,
        listOf(
            e("View"), e("ImageView"), e("ProgressBar"), e("$M.progressindicator.LinearProgressIndicator"),
            e("$M.progressindicator.CircularProgressIndicator"), e("SeekBar"), e("$M.slider.Slider"), e("RatingBar"),
            e("SearchView"), e("Space"), e("$M.divider.MaterialDivider"), e("CalendarView"), e("WebView"), e("VideoView"),
        ),
    ),
    PaletteCategory(
        "layouts", Res.string.le_palette_layouts,
        listOf(
            e("$X.constraintlayout.widget.ConstraintLayout"), e("LinearLayout"), e("FrameLayout"), e("TableLayout"),
            e("TableRow"), e("RelativeLayout"), e("GridLayout"), e("$X.coordinatorlayout.widget.CoordinatorLayout"),
        ),
    ),
    PaletteCategory(
        "containers", Res.string.le_palette_containers,
        listOf(
            e("ScrollView"), e("HorizontalScrollView"), e("$X.core.widget.NestedScrollView"),
            e("$X.recyclerview.widget.RecyclerView"), e("ListView"), e("$X.viewpager2.widget.ViewPager2"),
            e("$M.card.MaterialCardView", "$X.cardview.widget.CardView"), e("$M.appbar.AppBarLayout"),
            e("$M.appbar.MaterialToolbar", "$X.appcompat.widget.Toolbar", "Toolbar"), e("$M.tabs.TabLayout"),
            e("$M.bottomnavigation.BottomNavigationView"), e("$M.navigation.NavigationView"),
            e("$X.fragment.app.FragmentContainerView"),
        ),
    ),
)

/** A category resolved against what the project has: the first available tag of each entry. */
internal fun resolveCategory(category: PaletteCategory, byTag: Map<String, LayoutViewClass>): List<LayoutViewClass> =
    category.entries.mapNotNull { prefs -> prefs.firstNotNullOfOrNull { byTag[it] } }.distinctBy { it.tag }

/** Short display name: `TextInputLayout` rather than its package; the framework ones are already short. */
internal fun LayoutViewClass.displayName(): String = simpleName

/**
 * The palette panel: category chips (plus Project when it has its own views), a search over every view the
 * project can use, and a grid of tiles. A tap adds the view next to the selection; a long press picks it up to
 * drag onto the canvas or into the component tree ([dragSource]).
 */
@Composable
internal fun PalettePanel(
    classes: List<LayoutViewClass>?,
    onAdd: (LayoutViewClass) -> Unit,
    dragSource: @Composable (LayoutViewClass) -> Modifier,
    onClose: (() -> Unit)?,
    modifier: Modifier,
    /** The app theme's colours by attribute name (`colorPrimary`), for the tiles' previews. */
    themeColors: Map<String, Int> = emptyMap(),
) {
    val mini = MiniColors.of(themeColors)
    val byTag = remember(classes) { classes.orEmpty().associateBy { it.tag } }
    val project = remember(classes) { classes.orEmpty().filter { it.origin == ViewOrigin.PROJECT } }
    var category by remember { mutableStateOf("common") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(classes, category, query) {
        val q = query.trim().lowercase()
        when {
            q.isNotEmpty() -> classes.orEmpty().filter { it.simpleName.lowercase().contains(q) }
                .sortedWith(compareBy({ !it.simpleName.lowercase().startsWith(q) }, { it.origin != ViewOrigin.FRAMEWORK }, { it.simpleName }))
                .take(120)
            category == "project" -> project
            else -> PALETTE_CATEGORIES.firstOrNull { it.key == category }?.let { resolveCategory(it, byTag) }.orEmpty()
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.le_palette_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.le_palette_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BarButton(Sym.search, stringResource(Res.string.le_tree_search), selected = searching) {
                searching = !searching
                if (!searching) query = ""
            }
            if (onClose != null) BarButton(Sym.close, stringResource(Res.string.le_close), onClick = onClose)
        }
        if (searching) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SearchField(query, { query = it }, stringResource(Res.string.le_palette_search))
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PALETTE_CATEGORIES, key = { it.key }) { c ->
                    ChoiceChip(stringResource(c.label), selected = category == c.key, enabled = true) { category = c.key }
                }
                if (project.isNotEmpty()) item(key = "project") {
                    ChoiceChip(stringResource(Res.string.le_palette_project), selected = category == "project", enabled = true) { category = "project" }
                }
            }
        }
        if (classes == null) {
            Text(stringResource(Res.string.le_attrs_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        } else if (shown.isEmpty()) {
            Text(stringResource(Res.string.le_palette_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        LazyVerticalGrid(
            GridCells.Adaptive(80.dp),
            Modifier.weight(1f, fill = true),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.tag }) { cls ->
                PaletteTile(cls, mini, Modifier.then(dragSource(cls)).clickable { onAdd(cls) })
            }
        }
    }
}

/**
 * A palette tile: a small drawing of the view in the app's colours ([MiniWidget]) over its name, which wraps at
 * its words (`Constraint` / `Layout`) rather than being cut off.
 */
@Composable
private fun PaletteTile(cls: LayoutViewClass, colors: MiniColors, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .height(92.dp).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiniWidget(cls.tag, colors, Modifier.fillMaxWidth().height(48.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                cls.displayName().replace(CAMEL_HUMP, "\u200B"),
                style = ChromeTextSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Between a lower-case letter and the capital after it: where a class name may wrap. */
private val CAMEL_HUMP = Regex("(?<=[a-z])(?=[A-Z])")
