package dev.ide.interp.compose

/**
 * Stand-ins for the Android-only constructors of Compose's text platform-style types, for library bytecode
 * compiled against Android Compose and run against a host whose build of these classes lacks them (Compose for
 * Desktop). The motivating case is the project's own `material3-android`, which the preview interprets even on
 * the desktop (see `VmLibraryExecutor.PROJECT_PREFERRED_PREFIXES`): its `DefaultPlatformTextStyle_androidKt`
 * static initializer builds `PlatformTextStyle(includeFontPadding = false)`, a constructor the skiko build does
 * not have, and the failed class initialization leaves every Material3 typography style unbuilt.
 *
 * The Android-only parameters (`includeFontPadding`, `emojiSupportMatch`) have no desktop meaning, so each
 * substitute is the host's neutral style: what Compose for Desktop's own Material3 uses. On Android the real
 * constructors exist and this is never consulted.
 */
internal object PlatformStyleCompat {

    private const val TEXT_STYLE = "androidx/compose/ui/text/PlatformTextStyle"
    private const val SPAN_STYLE = "androidx/compose/ui/text/PlatformSpanStyle"
    private const val PARAGRAPH_STYLE = "androidx/compose/ui/text/PlatformParagraphStyle"

    /** A substitute instance for a missing `owner.<init>descriptor`, or null when [owner] is not covered. */
    fun construct(owner: String, loader: ClassLoader): Any? = when (owner) {
        TEXT_STYLE -> {
            val span = load(SPAN_STYLE, loader)
            val paragraph = load(PARAGRAPH_STYLE, loader)
            load(TEXT_STYLE, loader).getConstructor(span, paragraph).newInstance(null, null)
        }
        SPAN_STYLE, PARAGRAPH_STYLE -> companionDefault(load(owner, loader))
        else -> null
    }

    /** `Companion.Default`, the style every platform declares as its no-customization value. */
    private fun companionDefault(cls: Class<*>): Any? {
        val companion = cls.getField("Companion").get(null)
        return companion.javaClass.getMethod("getDefault").invoke(companion)
    }

    private fun load(internal: String, loader: ClassLoader): Class<*> =
        Class.forName(internal.replace('/', '.'), false, loader)
}
