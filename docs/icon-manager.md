# Icon Manager

Browse, preview and add icons to a project: the drawables it already has, the Material icon libraries, and
the `Icons.*` properties on a module's classpath. Plus an app-icon studio that composes a launcher icon and
writes the whole adaptive-icon file set.

Reachable from the editor's **More** sheet and the command palette ("Icon Manager"), and from any `res/`
node's **New** menu as **Image asset**, which opens it already scoped to that directory.

## Layout

The screen has three sections, one search field, and a detail pane that appears when an icon is selected.

| Section | What it lists | Actions on a selection |
| --- | --- | --- |
| **Project** | every `drawable`/`mipmap` in the module's own source sets, grouped by name with a badge for how many configurations it has | add to another module, insert a reference, copy it |
| **Libraries** | the registered icon repositories | add as a vector drawable, insert a reference, copy it, use as the app icon |
| **Compose** | the `Icons.*` properties the module's classpath actually provides | add as a drawable, insert `Icon(Icons.Filled.X, …)` with its imports, copy it |

Layout follows Material 3: a `PrimaryTabRow` for the sections, an `OutlinedTextField` search, `FilterChip`s
for the repositories, targets and colours, `SingleChoiceSegmentedButtonRow` for the style and folder, and a
tonal detail sheet whose actions sit in a `FlowRow` so they wrap instead of overflowing. Content is
width-capped and centred (880dp for the grid, 620dp for forms) rather than stretched, so the same screen reads
well on a phone and in a desktop window.

Everything renders from one neutral model (`UiDrawable`), the same one the resource preview pane uses, so a
grid tile looks exactly like the file that gets written. A single-colour icon is re-tinted to the theme's
foreground for display only, the way a real `Icon(tint = …)` would.

## Icon repositories

A repository is an extension point, `platform.iconRepository` (`IconRepository` in `android-support`), so a
plugin can contribute its own icon library and it appears in the picker with no change to the picker.

Two ship built in:

- **Material Symbols (bundled)**: a curated subset committed as a resource
  (`android-support/src/main/resources/dev/ide/android/support/icons/material-symbols.tsv`), outlined and
  filled, ordered by Google's own popularity metadata. Works offline, with no project open. Regenerate it with
  `.github/scripts/fetch_material_icons.py`; do not hand-edit it.
- **Material Symbols (all)**: the full upstream set, fetched from `google/material-design-icons` on demand.
  `requiresNetwork` is true, so nothing is downloaded until the user taps to load the catalogue; the index and
  each icon's SVG are cached under the shared cache dir, which makes the second visit offline.

Search ranking is shared (`IconSearch`) so every repository, including a plugin's, ranks identically: exact
name, then name prefix, then a word inside the name, then a keyword, then a category.

## Referencing an icon from your code

XML, Java and Kotlin name the same drawable three different ways, and Kotlin names it differently again inside
a Compose file, so "insert at the cursor" cannot be one string. `IconSnippets` (in `ide-ui-api`, so the backend
and the button label share it) decides the form:

| Buffer | A drawable | A Compose icon |
| --- | --- | --- |
| Kotlin, Compose imports present | `Icon(painterResource(R.drawable.ic_x), contentDescription = null)` | `Icon(Icons.Filled.X, contentDescription = null)` |
| Kotlin, plain | `R.drawable.ic_x` | `Icon(Icons.Filled.X, …)` |
| Java | `R.drawable.ic_x` | not offered |
| XML, caret between attribute quotes | `@drawable/ic_x` | not offered |
| XML, caret elsewhere in a tag | `android:src="@drawable/ic_x"` | not offered |

Two heuristics drive that, both computed from the live buffer rather than the file name: whether the file
imports Compose, and whether the caret sits between the quotes of an attribute (counted back to the enclosing
tag). Imports come along with the snippet, including `R` itself, which is skipped when the file is already in
the module's namespace because importing it there would be redundant. The button is labelled with the exact
reference it will write (`Insert R.drawable.ic_x`), and it is hidden entirely when the reference has no form in
that language.

Selecting a library icon and inserting it imports the icon first, so the reference cannot dangle. A project
icon and a Compose icon already exist and go straight through.

## Importing

An import writes one file and reports exactly what it did. The target is a **module plus source set** rather
than just a module, because per-flavour and per-build-type icons are a real need. A name that is already taken
is reported as a conflict with the path that holds it, and only a confirmed retry overwrites; replacing a
resource declared with a different extension deletes the old file, since aapt rejects two declarations of one
name.

SVG comes in through `SvgToVectorDrawable`, which lowers the parts VectorDrawable has no equivalent for:

- `<rect>`/`<circle>`/`<ellipse>`/`<line>`/`<polygon>`/`<polyline>` become path data.
- A `<g transform>` chain that is only scale and translate becomes a `<group>`, and the original path data is
  preserved byte for byte. A rotation, skew or general `matrix` cannot nest that way, so it is baked into the
  coordinates instead (arcs convert to cubics on the way, since a skewed arc is no longer an elliptical arc).
- Presentation attributes and inline `style="…"` resolve with inheritance, including `opacity` and `fill-rule`.
- A gradient fill has no `<path>` equivalent, so it degrades to the gradient's first stop and says so.

Anything lossy is surfaced as a warning on the detail pane rather than silently applied.

## App-icon studio

Composes a launcher icon from a background, a foreground and an optional themed layer, then writes:

- `mipmap-anydpi-v26/ic_launcher.xml` (and `_round`), the adaptive icon.
- the layers it references: a colour resource for a flat background, a vector for artwork, `drawable-nodpi`
  bytes for an imported bitmap.
- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` at 48/72/96/144/192 px, square and round. These are what
  pre-Android 8 devices and many launchers actually show. With rasters turned off, a vector `<layer-list>` in
  the unqualified `mipmap/` folder covers those devices instead.
- `ic_launcher-playstore.png` at 512 px, opaque, in the source-set root beside `res/` (AGP's own convention),
  so it is never packaged.
- the `<application android:icon>` / `android:roundIcon` attributes, patched surgically so comments and
  formatting survive.

### The geometry that matters

An adaptive icon is authored in a 108-unit box of which only the central 72 (the safe zone) is guaranteed
visible, and the launcher scales that box up so the safe zone fills the icon. Two consequences the code is
explicit about:

- `AppIconGenerator.composeLayer` scales source artwork into the **safe zone**, not the full box, which is why
  an imported 24dp icon is not cropped by a circular mask.
- `AppIconRaster` draws the layers across `pixels * 108 / 72`, centred, then masks back down. Rendering the
  box at face value would make every preview look zoomed out compared to the real launcher.

The live preview and the generated PNGs go through the same `drawAppIcon`, so the masks shown (circle,
squircle, rounded square, square) and the files written cannot drift apart.

### Where the work happens

Rasterising a vector needs a canvas, and the engine has none. So the split is:

1. the engine plans the change (`AppIconGenerator.plan`) and says which rasters it needs, at what size, with
   which mask;
2. the UI renders and encodes them (`AppIconRaster` plus the `encodeImagePng` expect/actual: Skia on desktop,
   `Bitmap.compress` on Android);
3. the engine writes the bytes it is handed and patches the manifest.

A plan is pure data and never touches the filesystem, so the studio can show the exact file list, and what it
would replace, before anything is committed. Output paths may step up one level (the store image), so each is
checked to still be inside the module before it is written.

## Compose icons

`ComposeIconIndex` reads which `Icons.*` properties a module can reference straight from its classpath: the
icon libraries generate one class per icon, so `androidx/compose/material/icons/filled/HomeKt.class` in a jar
is an exact answer, with no class loading and no compilation. A module with no icons library gets an empty tab
that offers to add the dependency rather than a misleading grid.

Their artwork is resolved by name from the Material repositories, which serve the same Google artwork under
the naming those use (`ShoppingCart` is `shopping_cart`).

**Not implemented:** evaluating a library's own `ImageVector` through the Compose interpreter. That is what a
*custom* icon library would need to render faithfully, and it is a bigger change than it looks: the
interpreter is reachable from `ide-core` only through the injected preview-runner port, and converting a real
`ImageVector` back to path data means mapping all of Compose's `PathNode` types. `IconManagerService`
`composeIconArtwork` documents the seam.

## Where things live

| Concern | Location |
| --- | --- |
| Icon repository SPI, search, Material providers | `android-support/.../icons/IconRepository.kt`, `BundledMaterialIcons.kt`, `MaterialSymbolsRemote.kt` |
| SVG conversion, path/affine maths, VectorDrawable writer | `android-support/.../icons/SvgToVectorDrawable.kt`, `SvgPath.kt`, `VectorDrawableWriter.kt` |
| App-icon generation, manifest patching | `android-support/.../icons/AppIconGenerator.kt`, `ManifestIconWriter.kt` |
| Vector model (groups, transforms, clips, fill rules) | `android-support/.../preview/DrawablePreview.kt` + `DrawablePreviewParser.kt` |
| Engine service (catalogue, targets, writing, app icon) | `ide-core/.../services/IconManagerService.kt`, `ComposeIconIndex.kt` |
| Backend seam and DTOs | `ide-core/.../backend/IconBackend.kt`, `ide-ui-api/.../backend/IconManager.kt` |
| Language-aware references | `ide-ui-api/.../backend/IconSnippets.kt` |
| Screens | `ide-ui/.../screens/IconManagerScreen.kt`, `AppIconStudioScreen.kt` (+ their state holders) |
| Rendering and rasterising | `ide-ui/.../editor/preview/DrawableCanvas.kt`, `AppIconRaster.kt`, `ImageDecode.kt` |
