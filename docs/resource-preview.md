# Android resource preview

CodeAssist renders Android resources visually next to their source — a third **Preview** view-mode
(beside Code and Blocks) that appears whenever the open file is a previewable resource. It draws
drawables, color swatches, and bitmaps with every `@color`/`@dimen`/`@drawable` reference resolved
against the *project's own* merged resources, so a preview reflects what the app will actually show.

## What gets previewed

`previewKindOf(path)` (in `ide-ui`, `editor/preview/ResourcePreviewPane.kt`) classifies a file by the
usual `res/` conventions:

- **Drawable** — XML under `res/drawable`, `res/color`, or `res/mipmap`. Rendered with a Compose
  `Canvas`. Covers `<shape>` (rectangle/oval/line/ring · solid/gradient/stroke/corners/size),
  `<vector>` (viewport + `<path>`/`<group>`, full SVG `pathData` incl. arcs), `<selector>` (the
  default state), `<layer-list>`/`<inset>` (composited, inset), `<color>`, `<ripple>`, and
  `<bitmap>`/`@drawable` references to image files.
- **Color** — a `res/values` file named `*color*`. Rendered as a swatch list (name · resolved hex),
  with `@color/…` indirection followed transitively.
- **Bitmap** — `png`/`webp`/`jpg`/`jpeg`/`gif`/`bmp` under `res/`. Decoded to a Compose `ImageBitmap`
  (Skia on desktop, `BitmapFactory` on Android via `decodeImageBytes` expect/actual) and shown over a
  transparency checkerboard. Image files open straight into Preview.

## How references resolve

The engine never renders against a guess. `IdeServices.drawablePreview`/`colorResources` build a
`DrawableResolver` over the module's merged `ResourceRepository` (project + dependency + AAR res, in
overlay order). Color literals (`#RGB`/`#ARGB`/`#RRGGBB`/`#AARRGGBB`) parse directly; `@color/…`
resolves through the repository (recursively); `@android:color/…` maps a small table of common
framework constants; `@dimen/…` reads the dimension value; and a nested `@drawable/…` is loaded and
parsed recursively (an image file becomes a `BitmapRef` carrying its path). Anything unresolved (a
framework drawable, a `?themeAttr`) falls back to a neutral placeholder rather than a wrong guess.

## Layering

```
android-support/preview/
  DrawablePreview.kt        # neutral, render-ready model (ARGB longs, dp floats, raw pathData) + AndroidColor + DrawableResolver
  DrawablePreviewParser.kt  # JAXP parser: drawable XML → DrawablePreview, references resolved
  ColorResources.kt         # <color>/<item type=color> extraction for the swatch list
android-support/resources/
  DrawableXmlCatalog.kt     # the drawable-XML grammar for completion (mirror of AndroidManifestCatalog)
ide-core/
  IdeServices              # drawablePreview / colorResources / resourceBytes + the repo-backed DrawableResolver
  IdeServicesBackend       # maps the engine model → neutral Ui* DTOs; new IdeBackend methods
  AndroidXmlCompletion     # routes res/drawable|color files to DrawableXmlCatalog
ide-ui/editor/preview/
  ResourcePreviewPane.kt   # the Preview view: dispatch by kind, load, render
  DrawableCanvas.kt        # DrawScope.drawUiDrawable — shapes/gradients/vectors/layers + checkerboard
  AndroidPathParser.kt     # SVG pathData → Compose Path
  ImageDecode.kt (+ .desktop/.android)  # bytes → ImageBitmap, expect/actual
```

The model is UI-toolkit-agnostic; `ide-core` maps it to neutral `Ui*` DTOs (`UiDrawable`, `UiColorEntry`,
…) handed across the `IdeBackend` port, exactly like the block editor's `UiBlockNode`.

## Completion

Drawable XML is now completed too. `DrawableXmlCatalog` describes the element grammar (root tags →
children → attributes, with enum values and `@color`/`@drawable` resource-type hints); the
`AndroidXmlContributor` routes any `res/drawable|color|mipmap` XML to it for tag/attribute/value
completion, while resource-reference completion (names from the resource index) works inside drawable
XML the same way it does in layouts.
