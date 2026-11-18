package dev.codeassist.csharp.completion

/**
 * Parses the output of [CsCompleter] for a signature request.
 *
 * Expected format:
 * - `sighelp<TAB>active signature<TAB>active parameter`
 * - One `sig<TAB>label<TAB>start,end;start,end...` line per overload, where each pair is a parameter's character
 *   range inside the label.
 * - Optional member documentation XML (escaped; see [CsDocText.unescape]).
 * - Optional parameter names separated by `;`.
 *
 * Pure, so it can be tested without a device.
 */
internal object CsSignatureLines {

    /** A parsed overload with its display label, parameter ranges, documentation, and parameter names. */
    class Signature(
        val label: String,
        val parameters: List<IntRange>,
        val documentation: String = "",
        val names: List<String> = emptyList(),
    )

    /** Parsed overloads and the currently active signature and parameter. */
    class Result(
        val signatures: List<Signature>,
        val activeSignature: Int,
        val activeParameter: Int,
    )

    /**
     * Parses [lines], returning null when there is no call under the caret or the output is malformed.
     */
    fun parse(lines: List<String>): Result? {
        val header = lines.firstOrNull { it.startsWith("sighelp\t") }?.split('\t') ?: return null
        if (header.size < 3) return null
        val activeSignature = header[1].toIntOrNull() ?: return null
        val activeParameter = header[2].toIntOrNull() ?: return null

        val signatures = lines.filter { it.startsWith("sig\t") }.mapNotNull { line ->
            val fields = line.split('\t')
            val label = fields.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ranges = fields.getOrElse(2) { "" }.split(';').mapNotNull { range(it, label.length) }
            val documentation = fields.getOrNull(3)?.let(CsDocText::unescape) ?: ""
            val names = fields.getOrElse(4) { "" }.split(';').filter { it.isNotEmpty() }

            Signature(label, ranges, documentation, names)
        }

        if (signatures.isEmpty()) return null

        return Result(
            signatures,
            activeSignature.coerceIn(0, signatures.size - 1),
            activeParameter.coerceAtLeast(0),
        )
    }

    /**
     * Parses a parameter's `[start, end)` range.
     * Invalid or out-of-bounds ranges are dropped rather than clamped to avoid misleading highlights.
     */
    private fun range(pair: String, length: Int): IntRange? {
        val parts = pair.split(',')
        if (parts.size != 2) return null

        val start = parts[0].toIntOrNull() ?: return null
        val end = parts[1].toIntOrNull() ?: return null

        return if (start in 0..end && end <= length) start until end else null
    }
}