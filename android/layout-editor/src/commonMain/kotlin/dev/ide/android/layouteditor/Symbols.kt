package dev.ide.android.layouteditor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.layout_editor_symbols
import dev.ide.android.layouteditor.generated.resources.layout_editor_symbols_filled
import org.jetbrains.compose.resources.Font

/**
 * The Material Symbols Rounded glyphs this plugin draws, by codepoint.
 *
 * A plugin cannot reach the IDE's own icon set (it is not SPI), so it ships its own: two subset fonts under
 * `composeResources/font/` holding only these glyphs (FILL 0 and FILL 1, ~15 KB each). They are addressed by
 * codepoint because the subset drops the ligature tables. To add one, append its name to the list the fonts
 * were cut from and re-run the fonttools `instancer` + `Subsetter.populate(unicodes=...)` recipe (the same one
 * the IDE's `CaSymbols` documents).
 */
internal object Sym {
    const val widgets = '\ue1bd' // widgets
    const val accountTree = '\ue97a' // account_tree
    const val tune = '\ue429' // tune
    const val code = '\ue86f' // code
    const val add = '\ue145' // add
    const val contentCopy = '\ue14d' // content_copy
    const val delete = '\ue92e' // delete
    const val northWest = '\uf1e2' // north_west
    const val moreHoriz = '\ue5d3' // more_horiz
    const val moreVert = '\ue5d4' // more_vert
    const val smartButton = '\uf1c1' // smart_button
    const val textFields = '\ue262' // text_fields
    const val image = '\ue3f4' // image
    const val input = '\ue890' // input
    const val edit = '\uf097' // edit
    const val toggleOn = '\ue9f6' // toggle_on
    const val label = '\ue893' // label
    const val viewList = '\ue8ef' // view_list
    const val checkBox = '\ue9de' // check_box
    const val radioButtonChecked = '\ue837' // radio_button_checked
    const val viewQuilt = '\ue8f1' // view_quilt
    const val viewAgenda = '\ue8e9' // view_agenda
    const val viewColumn = '\ue8ec' // view_column
    const val gridView = '\ue9b0' // grid_view
    const val cropSquare = '\ue3c6' // crop_square
    const val dragIndicator = '\ue945' // drag_indicator
    const val unfoldLess = '\ue5d6' // unfold_less
    const val unfoldMore = '\ue5d7' // unfold_more
    const val search = '\uef7a' // search
    const val close = '\ue5cd' // close
    const val check = '\ue668' // check
    const val expandMore = '\ue5cf' // expand_more
    const val chevronRight = '\ue5cc' // chevron_right
    const val designServices = '\uf10a' // design_services
    const val architecture = '\uea3b' // architecture
    const val verticalSplit = '\ue949' // vertical_split
    const val smartphone = '\ue7ba' // smartphone
    const val tablet = '\ue32f' // tablet
    const val arrowDropDown = '\ue5c5' // arrow_drop_down
    const val darkMode = '\ue51c' // dark_mode
    const val lightMode = '\ue518' // light_mode
    const val screenRotation = '\uf2d5' // screen_rotation
    const val addToPhotos = '\ue39d' // add_to_photos
    const val openInFull = '\uf1ce' // open_in_full
    const val link = '\ue250' // link
    const val warning = '\uf083' // warning
    const val error = '\uf8b6' // error
    const val build = '\uf8cd' // build
    const val visibility = '\ue8f4' // visibility
    const val visibilityOff = '\ue8f5' // visibility_off
    const val layers = '\ue53b' // layers
    const val fitScreen = '\uea10' // fit_screen
    const val zoomIn = '\ue8ff' // zoom_in
    const val zoomOut = '\ue900' // zoom_out
    const val linearScale = '\ue260' // linear_scale
    const val webAsset = '\ue069' // web_asset
    const val horizontalRule = '\uf108' // horizontal_rule
    const val spaceBar = '\ue256' // space_bar
    const val star = '\uf09a' // star
    const val progressActivity = '\ue9d0' // progress_activity
    const val wrapText = '\ue25b' // wrap_text
    const val swapVert = '\ue8d5' // swap_vert
    const val dashboard = '\ue871' // dashboard
    const val formatAlignCenter = '\ue234' // format_align_center
    const val formatListBulleted = '\ue241' // format_list_bulleted
    const val calendarMonth = '\uebcc' // calendar_month
    const val schedule = '\uefd6' // schedule
    const val tab = '\ue8d8' // tab
    const val viewCarousel = '\ue8eb' // view_carousel
    const val callToAction = '\ue06c' // call_to_action
    const val noteStack = '\uf562' // note_stack
    const val dataObject = '\uead3' // data_object
    const val info = '\ue88e' // info
    const val margin = '\ue9bb' // margin
    const val padding = '\ue9c8' // padding
    const val controlCamera = '\ue074' // control_camera
    const val balance = '\ueaf6' // balance
    const val aspectRatio = '\ue85b' // aspect_ratio
    const val title = '\ue264' // title
    const val formatSize = '\ue245' // format_size
    const val formatColorText = '\ue23c' // format_color_text
    const val formatColorFill = '\ue23a' // format_color_fill
    const val swapHoriz = '\ue8d4' // swap_horiz
    const val tag = '\ue9ef' // tag
    const val width = '\uf730' // width
    const val height = '\uea16' // height
    const val formatBold = '\ue238' // format_bold
    const val shortText = '\ue261' // short_text
    const val keyboard = '\ue312' // keyboard
    const val opacity = '\ue91c' // opacity
    const val colorize = '\ue3b8' // colorize
    const val accessibilityNew = '\ue92c' // accessibility_new
    const val history = '\ue8b3' // history
    const val expandLess = '\ue5ce' // expand_less
    const val photoLibrary = '\ue413' // photo_library
}

/**
 * One glyph at [size], sized in dp so the user's font scale does not grow the icons, and with the line box
 * trimmed so the glyph sits centred in its slot rather than low.
 */
@Composable
internal fun Symbol(
    glyph: Char,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
) {
    val font = Font(if (filled) Res.font.layout_editor_symbols_filled else Res.font.layout_editor_symbols)
    val family = remember(font) { FontFamily(font) }
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(
        text = glyph.toString(),
        modifier = modifier.size(size).semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        color = tint,
        textAlign = TextAlign.Center,
        maxLines = 1,
        style = TextStyle(
            fontFamily = family,
            fontSize = fontSize,
            lineHeight = fontSize,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        ),
    )
}

/** The glyph for a view class, by its simple name: what the palette, the tree and the selection chip show. */
internal fun glyphFor(tag: String): Char {
    val n = tag.substringAfterLast('.')
    return when {
        n == "include" -> Sym.noteStack
        n == "fragment" || n == "FragmentContainerView" -> Sym.dataObject
        n == "merge" -> Sym.layers
        n.endsWith("TextInputLayout") -> Sym.input
        n.endsWith("EditText") -> Sym.edit
        n.contains("Switch") -> Sym.toggleOn
        n.endsWith("CheckBox") -> Sym.checkBox
        n.endsWith("RadioButton") || n == "RadioGroup" -> Sym.radioButtonChecked
        n.endsWith("Chip") || n == "ChipGroup" -> Sym.label
        n.endsWith("ImageButton") || n.endsWith("FloatingActionButton") -> Sym.smartButton
        n.endsWith("Button") -> Sym.smartButton
        n.endsWith("ImageView") -> Sym.image
        n.endsWith("TextView") -> Sym.textFields
        n.endsWith("RecyclerView") || n == "ListView" || n == "GridView" -> Sym.viewList
        n == "ConstraintLayout" -> Sym.viewQuilt
        n == "LinearLayout" || n == "LinearLayoutCompat" -> Sym.viewAgenda
        n == "GridLayout" || n == "TableLayout" -> Sym.gridView
        n.endsWith("ScrollView") -> Sym.swapVert
        n.endsWith("Toolbar") || n == "AppBarLayout" -> Sym.webAsset
        n == "CoordinatorLayout" || n == "DrawerLayout" -> Sym.dashboard
        n.endsWith("CardView") -> Sym.callToAction
        n == "TabLayout" -> Sym.tab
        n.startsWith("ViewPager") -> Sym.viewCarousel
        n.contains("ProgressBar") || n.contains("ProgressIndicator") -> Sym.progressActivity
        n == "SeekBar" || n == "Slider" -> Sym.linearScale
        n == "RatingBar" -> Sym.star
        n == "Space" -> Sym.spaceBar
        n.endsWith("Divider") -> Sym.horizontalRule
        n == "FrameLayout" || n == "View" -> Sym.cropSquare
        n.endsWith("Layout") -> Sym.layers
        else -> Sym.widgets
    }
}
