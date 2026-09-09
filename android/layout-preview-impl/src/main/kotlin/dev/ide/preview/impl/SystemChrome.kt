package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.Props
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode

/**
 * The previewed window's system chrome — the platform views around the user's content: the status bar, the
 * action bar (app bar) with the activity title, and the window background. Derived from the activity's theme
 * + manifest label so the preview reads like the real screen rather than a bare layout. Colours/flags fall
 * back to Material defaults when the theme can't be resolved.
 */
data class PreviewChrome(
    val title: String,
    val showStatusBar: Boolean = true,
    val showActionBar: Boolean = true,
    val fullscreen: Boolean = false,
    val statusBarColor: Int = 0xFF3700B3.toInt(),
    val actionBarColor: Int = 0xFF6200EE.toInt(),
    val actionBarTextColor: Int = 0xFFFFFFFF.toInt(),
    val windowBackground: Int = 0xFFFAFAFA.toInt(),
) {
    companion object {
        const val STATUS_BAR_DP = 24
        const val ACTION_BAR_DP = 56

        /** Build chrome for [themeName] (the activity's theme) titled [title], reading colours from [repo]. */
        fun fromTheme(repo: ResourceRepository, res: dev.ide.preview.PreviewResources, themeName: String?, title: String): PreviewChrome {
            if (themeName.isNullOrBlank()) return PreviewChrome(title = title)
            val theme = ThemeResolver(repo, res)
            val primary = theme.color(themeName, "colorPrimary", "colorPrimaryDark") ?: 0xFF6200EE.toInt()
            val status = theme.color(themeName, "statusBarColor", "colorPrimaryDark") ?: darken(primary)
            val noActionBar = theme.hasNoActionBar(themeName) ||
                theme.flag(themeName, "windowActionBar") == false ||
                theme.flag(themeName, "windowNoTitle") == true
            val fullscreen = theme.flag(themeName, "windowFullscreen", "windowFullScreen") == true
            val windowBg = theme.color(themeName, "android:windowBackground", "windowBackground") ?: 0xFFFAFAFA.toInt()
            return PreviewChrome(
                title = title,
                showActionBar = !noActionBar,
                fullscreen = fullscreen,
                statusBarColor = status,
                actionBarColor = primary,
                windowBackground = windowBg,
            )
        }

        /** A slightly darker shade, the conventional status-bar tint when only `colorPrimary` is known. */
        private fun darken(argb: Int): Int {
            val a = argb ushr 24 and 0xFF
            val r = ((argb ushr 16 and 0xFF) * 0.78f).toInt()
            val g = ((argb ushr 8 and 0xFF) * 0.78f).toInt()
            val b = ((argb and 0xFF) * 0.78f).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** Wraps inflated content in its window chrome, producing the root the engine measures/draws. */
object SystemChrome {

    /**
     * A vertical stack `[status bar?] [action bar?] [content fills remaining]`. The content node is given
     * `weight=1` + a 0-height base so it fills the space below the bars (the standard Android weight idiom),
     * regardless of the layout file's own root size.
     */
    fun wrap(content: RenderNode, chrome: PreviewChrome, ctx: RenderContext): RenderNode {
        val density = ctx.density
        val root = RenderNode().apply {
            renderer = LinearLayoutRenderer
            tag = "decor"
            props.orientation = Props.VERTICAL
            props.layoutWidth = Props.MATCH_PARENT
            props.layoutHeight = Props.MATCH_PARENT
            props.backgroundColor = chrome.windowBackground
        }

        if (chrome.showStatusBar && !chrome.fullscreen) {
            root.children.add(bar((PreviewChrome.STATUS_BAR_DP * density).toInt(), chrome.statusBarColor, "statusBar"))
        }
        if (chrome.showActionBar) {
            val appBar = bar((PreviewChrome.ACTION_BAR_DP * density).toInt(), chrome.actionBarColor, "actionBar")
            appBar.children.add(actionBarTitle(chrome, ctx))
            root.children.add(appBar)
        }

        content.props.weight = 1f
        content.props.layoutHeight = 0
        root.children.add(content)
        return root
    }

    private fun bar(heightPx: Int, color: Int, tag: String): RenderNode = RenderNode().apply {
        renderer = FrameLayoutRenderer
        this.tag = tag
        props.layoutWidth = Props.MATCH_PARENT
        props.layoutHeight = heightPx
        props.backgroundColor = color
    }

    private fun actionBarTitle(chrome: PreviewChrome, ctx: RenderContext): RenderNode = RenderNode().apply {
        renderer = TextRenderer
        tag = "title"
        props.text = chrome.title
        props.textColor = chrome.actionBarTextColor
        props.bold = true
        props.textSizePx = 20f * ctx.scaledDensity
        props.layoutWidth = Props.WRAP_CONTENT
        props.layoutHeight = Props.WRAP_CONTENT
        props.paddingLeft = (16 * ctx.density).toInt()
        // Roughly centre the title in the 56dp bar.
        props.marginTop = ((PreviewChrome.ACTION_BAR_DP - 20) / 2 * ctx.density).toInt()
    }
}
