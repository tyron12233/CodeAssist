package dev.ide.ui.editor.core

/**
 * Size thresholds above which the editor treats a file as "large" and suppresses the memory-heavy, always-on
 * code intelligence: live analysis, semantic coloring, code folding, inlay hints, completion auto-popup, the
 * whole-file occurrence scan, and the structure outline. Cheap line-based syntax highlighting and plain text
 * editing stay on.
 *
 * Parsing and resolving a very large file builds an AST plus a symbol index many times the source size; on the
 * bounded Dalvik heap of a budget device that can exhaust the process heap (a small but recurring on-device
 * OutOfMemoryError, always on low-RAM hardware at the heap ceiling). Gating the heavy passes by size keeps the
 * peak allocation bounded while leaving the file fully editable.
 *
 * The char limit mirrors IntelliJ's `idea.max.intellisense.filesize` default (2500 KB); the line limit catches
 * a file that fits the char budget but has so many lines that per-line structures dominate instead.
 */
const val LARGE_FILE_CHAR_LIMIT: Int = 2_500_000
const val LARGE_FILE_LINE_LIMIT: Int = 50_000

/** True when [this] exceeds either large-file threshold; see [LARGE_FILE_CHAR_LIMIT] / [LARGE_FILE_LINE_LIMIT]. */
fun EditorDocument.isLarge(): Boolean = length > LARGE_FILE_CHAR_LIMIT || lineCount > LARGE_FILE_LINE_LIMIT
