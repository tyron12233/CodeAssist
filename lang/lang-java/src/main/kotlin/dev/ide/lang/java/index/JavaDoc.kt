package dev.ide.lang.java.index

/** Turns a raw `/** … */` javadoc block into readable plain text for a doc panel (port of the JDT helper). */
internal object JavaDoc {
    fun clean(raw: String): String {
        val lines = raw.lineSequence()
            .map {
                it.trim().removePrefix("/**").removePrefix("/*")
                    .let { l -> if (l.endsWith("*/")) l.dropLast(2) else l }
                    .trim().removePrefix("*").trim()
            }
            .toList()
        return lines
            .filterNot { it.startsWith("@") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(2000)
    }
}
