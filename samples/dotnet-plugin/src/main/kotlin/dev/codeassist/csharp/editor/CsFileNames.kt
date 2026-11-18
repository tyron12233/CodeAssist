package dev.codeassist.csharp.editor

import dev.codeassist.csharp.project.CsMainScan

/**
 * Checks the convention of naming a C# file after its single public type.
 *
 * C# does not require this convention, so a mismatch is only a hint.
 */
internal object CsFileNames {

    /** A public type declaration and the location of its name in the source. */
    class Mismatch(
        val name: String,
        val start: Int,
        val end: Int,
    )

    private val typeDeclaration = Regex(
        """^\s*(?:\[[^\]]*]\s*)*((?:(?:public|internal|static|sealed|abstract|partial|readonly|ref|unsafe|file|""" +
            """protected|private|new)\s+)*)""" +
            """(?:class|struct|interface|enum|record(?:\s+(?:class|struct))?|delegate\s+\S+)\s+([A-Za-z_]\w*)""",
    )

    /**
     * Returns the public type whose name does not match [fileName], or null.
     *
     * Only files containing exactly one public type are considered. Files with top-level
     * statements are ignored. Partial types may use names such as `Person.Part.cs`.
     */
    fun mismatch(text: String, fileName: String): Mismatch? {
        if (!fileName.endsWith(".cs", ignoreCase = true)) return null
        if (CsMainScan.topLevel(text) != null) return null

        val stem = fileName.dropLast(3)
        val onlyType = publicTypes(text).singleOrNull() ?: return null

        if (stem == onlyType.name || stem.startsWith("${onlyType.name}.")) {
            return null
        }

        return onlyType
    }

    /** Finds public type declarations at namespace scope, in source order. */
    internal fun publicTypes(text: String): List<Mismatch> {
        val masked = CsMainScan.mask(text)
        val found = ArrayList<Mismatch>()
        val namespaceScopes = ArrayList<Boolean>()
        var headerStart = 0

        for (i in masked.indices) {
            val char = masked[i]
            if (char != '{' && char != '}' && char != ';') continue

            if (char == '}') {
                if (namespaceScopes.isNotEmpty()) {
                    namespaceScopes.removeAt(namespaceScopes.lastIndex)
                }
            } else {
                val header = masked.substring(headerStart, i)

                if (namespaceScopes.all { it }) {
                    findPublicType(header, headerStart)?.let(found::add)
                }

                if (char == '{') {
                    namespaceScopes.add(Regex("""\bnamespace\b""").containsMatchIn(header))
                }
            }

            headerStart = i + 1
        }

        return found
    }

    private fun findPublicType(header: String, headerStart: Int): Mismatch? {
        val match = typeDeclaration.find(header) ?: return null
        if (!Regex("""\bpublic\b""").containsMatchIn(match.groupValues[1])) return null

        val name = match.groups[2] ?: return null
        val start = headerStart + name.range.first

        return Mismatch(
            name = name.value,
            start = start,
            end = start + name.value.length,
        )
    }
}
