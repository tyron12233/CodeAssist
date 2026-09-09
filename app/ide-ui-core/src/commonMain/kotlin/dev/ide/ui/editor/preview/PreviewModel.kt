package dev.ide.ui.editor.preview

/**
 * The preview model: which preview a file qualifies for, and what went wrong rendering one.
 *
 * These are plain data, but they were declared inside the composable files that render them
 * (ResourcePreviewPane.kt, PreviewSurface.kt) -- which put them above the editor state and app state that
 * read them, and made the preview UI impossible to separate from the model. They live here, in the module
 * everything else builds on, for that reason. The package is deliberately unchanged, so every existing
 * `import dev.ide.ui.editor.preview.*` still resolves.
 */

/** Which resource preview a file gets — or null when it has none (so the Preview toggle stays hidden). */
enum class PreviewKind { DRAWABLE, COLOR, BITMAP }

private val IMAGE_EXTS = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")

/**
 * The preview a file qualifies for, by Android `res/` convention: a drawable/color/mipmap XML renders as a
 * drawable, an image file as a bitmap, and a `res/values` file named `*color*` as a color swatch list.
 */
fun previewKindOf(path: String): PreviewKind? {
    val p = path.replace('\\', '/').lowercase()
    if (!p.contains("/res/")) return null
    val file = p.substringAfterLast('/')
    val ext = file.substringAfterLast('.', "")
    val folder = p.substringBeforeLast('/').substringAfterLast('/').substringBefore('-')
    return when (ext) {
        "xml" if (folder == "drawable" || folder == "color" || folder == "mipmap") -> PreviewKind.DRAWABLE
        in IMAGE_EXTS -> PreviewKind.BITMAP
        "xml" if folder == "values" && file.contains("color") -> PreviewKind.COLOR
        else -> null
    }
}

/** Severity of a [PreviewIssue] — a warning (amber) or an error (red), driving the chip's icon + tint. */
enum class PreviewIssueLevel { WARNING, ERROR }

/**
 * A problem surfaced by a preview, shown in the shared [PreviewProblemChip]: a layout-inflation warning
 * (unknown tag, unresolved include) or a Compose interpret/render failure.
 */
data class PreviewIssue(val level: PreviewIssueLevel, val title: String, val message: String)
