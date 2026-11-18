package dev.codeassist.csharp.completion

/**
 * Parses the completion output of [dev.codeassist.csharp.sdk.CsCompleter].
 *
 * Expected format, one candidate per line, with fields separated by tabs:
 * `item<TAB>name<TAB>kind<TAB>detail<TAB>takesArguments 0|1<TAB>container<TAB>deprecated 0|1<TAB>overloads<TAB>documentation`
 *
 * Documentation is optional and escaped (see [CsDocText.unescape]).
 * Pure, so it can be tested without a device.
 */
internal object CsCompletionItems {

    /**
     * A completion candidate.
     *
     * [kind] is one of `method`, `extension`, `property`, `field`, `constant`, `enummember`, `event`, `local`,
     * `parameter`, `namespace`, `typeparameter`, `class`, `struct`, `interface`, `enum`, or `delegate`.
     *
     * [detail] is the value's type or a method's return type.
     * [container] is the declaring type or namespace.
     */
    class Item(
        val name: String,
        val kind: String,
        val detail: String,
        val takesArguments: Boolean,
        val container: String,
        val deprecated: Boolean,
        val overloads: Int,
        /** The member's summary documentation XML, or empty. See [CsDocText]. */
        val documentation: String = "",
    ) {
        val isMethod: Boolean get() = kind == "method" || kind == "extension"
        val isType: Boolean get() = kind in TYPE_KINDS
    }

    private val TYPE_KINDS = setOf("class", "struct", "interface", "enum", "delegate")

    /** Parses valid completion candidates, skipping malformed or unrelated lines. */
    fun parse(lines: List<String>): List<Item> = lines.mapNotNull { line ->
        if (!line.startsWith("item\t")) return@mapNotNull null

        val fields = line.split('\t')
        if (fields.size < 8 || fields[1].isEmpty()) return@mapNotNull null

        Item(
            name = fields[1],
            kind = fields[2],
            detail = fields[3],
            takesArguments = fields[4] == "1",
            container = fields[5],
            deprecated = fields[6] == "1",
            overloads = fields[7].toIntOrNull() ?: 1,
            documentation = fields.getOrNull(8)?.let(CsDocText::unescape) ?: "",
        )
    }
}