package dev.ide.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The custom rounded line-icon set, recreated as Compose [ImageVector]s on
 * a 24-grid (stroke 1.7, round caps/joins). Each vector is drawn in opaque black so callers can recolor
 * it with `Icon(tint = …)`. Multi-shape glyphs (a circle + a stroke, etc.) are composed from sub-paths;
 * circles/rounded-rects are emitted as SVG arc paths so one parser handles everything.
 */
object CaIcons {
    private class Sub(val d: String, val filled: Boolean)

    private fun s(d: String) = Sub(d, false)
    private fun f(d: String) = Sub(d, true)

    private fun num(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    /** A full circle as an SVG arc path (two semicircle arcs). */
    private fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false): Sub {
        val d = "M${num(cx - r)} ${num(cy)} a ${num(r)} ${num(r)} 0 1 0 ${num(2 * r)} 0 " +
            "a ${num(r)} ${num(r)} 0 1 0 ${num(-2 * r)} 0 Z"
        return Sub(d, filled)
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, filled: Boolean = false): Sub {
        val d = "M${num(x + rx)} ${num(y)} h ${num(w - 2 * rx)} a ${num(rx)} ${num(rx)} 0 0 1 ${num(rx)} ${num(rx)} " +
            "v ${num(h - 2 * rx)} a ${num(rx)} ${num(rx)} 0 0 1 ${num(-rx)} ${num(rx)} " +
            "h ${num(-(w - 2 * rx))} a ${num(rx)} ${num(rx)} 0 0 1 ${num(-rx)} ${num(-rx)} " +
            "v ${num(-(h - 2 * rx))} a ${num(rx)} ${num(rx)} 0 0 1 ${num(rx)} ${num(-rx)} Z"
        return Sub(d, filled)
    }

    private fun build(name: String, vararg subs: Sub): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (sub in subs) {
            val nodes = PathParser().parsePathString(sub.d).toNodes()
            if (sub.filled) {
                b.addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val chevronRight = build("chevron-right", s("M9 6l6 6-6 6"))
    val chevronLeft = build("chevron-left", s("M15 6l-6 6 6 6"))
    val chevronDown = build("chevron-down", s("M6 9l6 6 6-6"))
    val chevronUp = build("chevron-up", s("M6 15l6-6 6 6"))
    val caretRight = build("caret-right", f("M10 7l5 5-5 5z"))
    val caretDown = build("caret-down", f("M7 10l5 5 5-5z"))
    val close = build("close", s("M6 6l12 12M18 6L6 18"))
    val check = build("check", s("M5 12.5l4.5 4.5L19 6.5"))
    val plus = build("plus", s("M12 5v14M5 12h14"))
    val search = build("search", circle(11f, 11f, 6.5f), s("M16 16l4.5 4.5"))
    val command = build(
        "command",
        s("M8 8a2.5 2.5 0 1 0-2.5 2.5H18a2.5 2.5 0 1 1-2.5 2.5V8.5A2.5 2.5 0 1 1 18 11H6a2.5 2.5 0 1 0 2.5 2.5V8z"),
    )
    val play = build("play", f("M8 5.5l11 6.5-11 6.5z"))
    val save = build(
        "save",
        s("M5 5h10l4 4v10H5Z"), // body with a folded top-right corner
        s("M9 5v4h5V5"),        // write-protect slot
        s("M8 19v-6h8v6"),      // label area
    )
    val stop = build("stop", roundRect(7f, 7f, 10f, 10f, 2.5f, filled = true))
    val copy = build(
        "copy",
        roundRect(8.5f, 8.5f, 12f, 12f, 2.5f),                                          // front sheet
        s("M15.5 8.5V6a2.5 2.5 0 0 0-2.5-2.5H6A2.5 2.5 0 0 0 3.5 6v7A2.5 2.5 0 0 0 6 15.5h2.5"), // back sheet
    )
    val terminal = build("terminal", roundRect(3.5f, 4.5f, 17f, 15f, 3f), s("M7 9l3 2.5L7 14M12.5 14.5h4"))
    val gear = build(
        "gear",
        circle(12f, 12f, 3f),
        s("M12 3v2.5M12 18.5V21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M3 12h2.5M18.5 12H21M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8"),
    )
    val folder = build("folder", s("M3.5 7.5a2 2 0 0 1 2-2h3l2 2h6a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2z"))
    val folderOpen = build(
        "folder-open",
        s("M3.5 7.5a2 2 0 0 1 2-2h3l2 2h6a2 2 0 0 1 2 2v1h-13l-2 6.5"),
        s("M4.5 18.5h12.8l2.2-7H6.7z"),
    )
    val file = build(
        "file",
        s("M6.5 3.5h7l5 5v10a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 18.5v-13A1.5 1.5 0 0 1 6.5 3.5z"),
        s("M13 3.5V8.5h5"),
    )
    val docText = build(
        "doc-text",
        s("M6.5 3.5h7l5 5v10a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 18.5v-13A1.5 1.5 0 0 1 6.5 3.5z"),
        s("M13 3.5V8.5h5M8.5 12.5h7M8.5 15.5h7"),
    )
    val ellipsis = build("ellipsis", circle(5.5f, 12f, 1.4f, true), circle(12f, 12f, 1.4f, true), circle(18.5f, 12f, 1.4f, true))
    val error = build("error", circle(12f, 12f, 8.5f), s("M9 9l6 6M15 9l-6 6"))
    val warning = build("warning", s("M12 4.5l8.5 14.5h-17z"), s("M12 10v4.5"), circle(12f, 17f, 0.6f, true))
    val info = build("info", circle(12f, 12f, 8.5f), s("M12 11v5.5"), circle(12f, 8f, 0.7f, true))
    val dot = build("dot", circle(12f, 12f, 3.5f, true))
    val lightbulb = build(
        "lightbulb",
        s("M8.5 14a5 5 0 1 1 7 0c-.8.8-1.2 1.4-1.3 2.5h-4.4c-.1-1.1-.5-1.7-1.3-2.5z"),
        s("M9.7 19.5h4.6M10.3 21.5h3.4"),
    )
    val refresh = build("refresh", s("M19 7a8 8 0 1 0 1.5 5M19 4v3.5h-3.5"))
    val arrowRight = build("arrow-right", s("M5 12h13M13 6l6 6-6 6"))
    /** Arrow into a tray — import files from outside the app. */
    val download = build("download", s("M12 4v10M8 10.5l4 4 4-4"), s("M5 18.5h14"))
    /** Arrow up out of a tray — share/export a file to another app. */
    val share = build("share", s("M12 16V4.5M8 8l4-4 4 4"), s("M6 12v6.5a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V12"))
    val sidebar = build("sidebar", roundRect(3.5f, 5f, 17f, 14f, 3f), s("M9.5 5v14"))
    val panelRight = build("panel-right", roundRect(3.5f, 5f, 17f, 14f, 3f), s("M15 5v14"))
    val split = build("split", roundRect(3.5f, 5f, 17f, 14f, 3f), s("M12 5v14"))
    val layers = build("layers", s("M12 4l8 4-8 4-8-4zM4 12l8 4 8-4M4 16l8 4 8-4"))
    val code = build("code", s("M9 8l-4 4 4 4M15 8l4 4-4 4"))
    val eye = build("eye", s("M2.5 12s3.4-6.3 9.5-6.3S21.5 12 21.5 12 18.1 18.3 12 18.3 2.5 12 2.5 12z"), circle(12f, 12f, 2.7f))
    val gitBranch = build("git-branch", circle(7f, 6f, 2.5f), circle(7f, 18f, 2.5f), circle(17f, 9f, 2.5f), s("M7 8.5v7M17 11.5c0 3-4 2.5-7 4"))
    val pin = build("pin", s("M9 4h6l-.8 5 2.3 2.5h-9L9.8 9z"), s("M12 13.5V20"))
    /** A four-point concave star — code completion. */
    val sparkle = build(
        "sparkle",
        s("M12 3.5C12.8 9 15 11.2 20.5 12 15 12.8 12.8 15 12 20.5 11.2 15 9 12.8 3.5 12 9 11.2 11.2 9 12 3.5Z"),
        s("M18.5 4.2c.2 1.5.6 1.9 2.1 2.1-1.5.2-1.9.6-2.1 2.1-.2-1.5-.6-1.9-2.1-2.1 1.5-.2 1.9-.6 2.1-2.1z"),
    )
    /** Curly braces `{ }` — the block/projectional editor. */
    val braces = build(
        "braces",
        s("M9.5 4.5c-1.6 0-2.3.9-2.3 2.6 0 1.2.1 2.1-1.4 2.9 1.5.8 1.4 1.7 1.4 2.9 0 1.7.7 2.6 2.3 2.6"),
        s("M14.5 4.5c1.6 0 2.3.9 2.3 2.6 0 1.2-.1 2.1 1.4 2.9-1.5.8-1.4 1.7-1.4 2.9 0 1.7-.7 2.6-2.3 2.6"),
    )
    /** A mallet/hammer — build & run. */
    val hammer = build("hammer", roundRect(4.5f, 4.5f, 15f, 5f, 2f), s("M12 9.5V19.5"))

    // ---- file-tree node icons (resolved by TreeIcons) ----
    /** A package: a square divided into a grid. */
    val pkg = build("pkg", roundRect(4.5f, 4.5f, 15f, 15f, 2.5f), s("M12 4.5v15M4.5 12h15"))
    /** The Android robot head — for android modules and the manifest. */
    val androidLogo = build(
        "android-logo",
        s("M5 13.5a7 7 0 0 1 14 0z"),
        s("M8 6.5l1.7 2.6M16 6.5l-1.7 2.6"),
        circle(9.6f, 10.5f, 0.75f, true),
        circle(14.4f, 10.5f, 0.75f, true),
    )
    /** A framed picture (sun + mountain) — Android `res/`. */
    val image = build(
        "image",
        roundRect(3.5f, 5f, 17f, 14f, 3f),
        circle(8.5f, 9.5f, 1.4f),
        s("M5 17.5l4-4.5 3 3 3.5-4 4.5 5"),
    )
    /** An isometric box — `assets/`. */
    val box = build(
        "box",
        s("M12 3.3l7.5 4.2v9.2L12 20.9 4.5 16.7V7.5z"),
        s("M4.6 7.5l7.4 4.2 7.4-4.2M12 11.7v9.2"),
    )
    /** Three stacked bars — generic resources. */
    val resources = build("resources", s("M5 7.5h14M5 12h14M5 16.5h9"))
}
