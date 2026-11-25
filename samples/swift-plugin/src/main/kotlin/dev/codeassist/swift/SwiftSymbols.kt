package dev.codeassist.swift

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws SF Symbols with open icons. SF Symbols may not be shipped or drawn off Apple's devices, so each SF name maps to
 * the closest Material Symbols Rounded icon (Apache License 2.0; tools/swift-sdk/symbols): `.fill` names use the font's
 * FILL axis, the symbol's weight its wght axis. The frame comes from the engine, which sizes a symbol exactly as iOS
 * does (measured per point size); the icon is fitted and centred in it.
 */
class SwiftSymbols(private val dir: Path) {
    private val map: Map<String, Int> by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("assets/symbols/sf-symbols.map") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1).trim().toInt(16)
            }.toMap()
        }
    }

    private val font: File by lazy {
        val target = dir.resolve("MaterialSymbolsRounded-subset.ttf")
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(dir)
            javaClass.classLoader?.getResourceAsStream("assets/symbols/MaterialSymbolsRounded-subset.ttf")
                ?.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        target.toFile()
    }

    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val bounds = ConcurrentHashMap<String, Rect>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    data class Glyph(val codepoint: Int, val filled: Boolean)

    /** The icon for SF name [name]: its own entry, its `.fill`-less form filled, or the name less its last part. */
    fun resolve(name: String): Glyph? {
        var n = name
        var filled = false
        while (n.isNotEmpty()) {
            map[n]?.let { return Glyph(it, filled) }
            if (n.endsWith(".fill")) {
                filled = true
                n = n.removeSuffix(".fill")
                continue
            }
            // person.fill.checkmark and the like: a .fill in the middle fills what remains.
            if (n.contains(".fill.")) {
                filled = true
                n = n.replace(".fill.", ".")
                continue
            }
            val dot = n.lastIndexOf('.')
            if (dot < 0) return null
            n = n.substring(0, dot)
        }
        return null
    }

    private fun typeface(filled: Boolean, weight: Int, size: Float): Typeface {
        val wght = weight.coerceIn(100, 700)
        val opsz = size.coerceIn(20f, 48f).toInt()
        val key = "$filled/$wght/$opsz"
        return typefaces.getOrPut(key) {
            Typeface.Builder(font).setFontVariationSettings("'FILL' ${if (filled) 1 else 0}, 'wght' $wght, 'opsz' $opsz").build()
        }
    }

    /** Draw SF symbol [name] fitted into [frame] (points). False when no icon stands for it. */
    fun draw(canvas: Canvas, name: String, frame: RectF, size: Float, weight: Int, color: Int): Boolean {
        val glyph = resolve(name) ?: return false
        val text = String(Character.toChars(glyph.codepoint))
        synchronized(paint) {
            paint.typeface = typeface(glyph.filled, weight, size)
            paint.color = color
            paint.textSize = 100f
            val key = "${glyph.codepoint}/${glyph.filled}/$weight"
            val b = bounds.getOrPut(key) { Rect().also { paint.getTextBounds(text, 0, text.length, it) } }
            if (b.width() <= 0 || b.height() <= 0) return false
            val scale = minOf(frame.width() / b.width(), frame.height() / b.height())
            paint.textSize = 100f * scale
            val x = frame.centerX() - (b.left + b.width() / 2f) * scale
            val y = frame.centerY() - (b.top + b.height() / 2f) * scale
            canvas.drawText(text, x, y, paint)
        }
        return true
    }
}
