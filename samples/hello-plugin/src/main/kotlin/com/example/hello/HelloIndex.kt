package com.example.hello

import dev.ide.index.Externalizer
import dev.ide.index.INDEX_QUERIES
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionResult

/** Where a `greet <name>` line is: the file's index id (resolved with `IndexQueries.filePath`) and its line. */
data class GreetingSite(val fileId: Int, val line: Int)

/**
 * A plugin's own index, over the plugin's own file type: every `greet <name>` line of every `.hello` file in
 * the project, keyed by the name.
 *
 * The IDE reads only `.java` and `.kt` files under source roots unless an index asks for more, which is what
 * [sourceFileExtensions] does. Once asked, `.hello` files are walked, saved on disk with the rest of the
 * index, and re-indexed when the user saves or creates one. [findGreetings] reads it back.
 */
class HelloGreetingIndex : IndexExtension<String, GreetingSite> {
    override val id = ID

    // Bump this whenever what index() emits, or the bytes the externalizer writes, change.
    override val version = 1
    override val keyDescriptor = StringKeyDescriptor
    override val matching = MatchingMode.PREFIX_ONLY
    override val sourceFileExtensions = setOf(".hello")

    // Every index sees every file the walk reads, the IDE's .java and .kt included, so select ours.
    override val inputFilter = InputFilter {
        it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".hello") == true
    }

    override val valueExternalizer = object : Externalizer<GreetingSite> {
        override fun write(out: DataWriter, value: GreetingSite) {
            out.writeInt(value.fileId)
            out.writeInt(value.line)
        }

        override fun read(inp: DataReader) = GreetingSite(inp.readInt(), inp.readInt())
    }

    // Runs on indexing threads, several files at once: no I/O and no state, only the input.
    override fun index(input: IndexInput): Map<String, Collection<GreetingSite>> {
        val text = input.text() ?: return emptyMap()
        val out = HashMap<String, MutableList<GreetingSite>>()
        text.lineSequence().forEachIndexed { line, raw ->
            val name = GREET.matchEntire(raw.trim())?.groupValues?.get(1) ?: return@forEachIndexed
            out.getOrPut(name) { ArrayList() }.add(GreetingSite(input.fileId, line))
        }
        return out
    }

    companion object {
        /** Prefixed with the plugin id, since index ids share one namespace with the IDE's and other plugins'. */
        val ID = IndexId("com.example.hello.greetings")
        private val GREET = Regex("""greet\s+(\w+).*""")
    }
}

/** The word under the caret in a `.hello` file, which is a name if the line greets someone. */
internal fun greetedNameAtCaret(ctx: ActionContext): String? {
    if (ctx.activeFilePath?.endsWith(".hello") != true) return null
    val text = ctx.documentText ?: return null
    val at = ctx.caret?.offset ?: ctx.selectionStart ?: return null
    var start = at.coerceIn(0, text.length)
    var end = start
    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
    while (end < text.length && text[end].isLetterOrDigit()) end++
    return text.substring(start, end).takeIf { it.isNotEmpty() && it != "greet" }
}

/**
 * The editor action "Hello: where else is this name greeted?": every `.hello` file and line that greets the
 * name under the caret, read from [HelloGreetingIndex] through `INDEX_QUERIES` rather than by opening every file.
 */
internal fun findGreetings(ctx: ActionContext): ActionResult {
    val name = greetedNameAtCaret(ctx) ?: return ActionResult.message("Put the caret on a name in a .hello file.")
    // Null with no project open. A host older than SPI 3.9.0 has no INDEX_QUERIES at all, so naming it throws.
    val index = try {
        ctx.workspaceServices.getServiceOrNull(INDEX_QUERIES)
    } catch (_: LinkageError) {
        return ActionResult.message("This version of CodeAssist cannot search a plugin's index.")
    } ?: return ActionResult.message("Open a project to search its .hello files.")
    // Before the first build finishes, an empty answer means "not indexed yet", not "nobody is greeted".
    if (!index.status.ready) return ActionResult.message("Still indexing; try again in a moment.")

    val sites = index.exact<GreetingSite>(HelloGreetingIndex.ID, name).take(20).toList()
    if (sites.isEmpty()) return ActionResult.message("No .hello file greets $name.")
    val lines = sites.joinToString("\n") { site ->
        val file = index.filePath(site.fileId)?.substringAfterLast('/') ?: "?"
        "$file, line ${site.line + 1}"
    }
    return ActionResult.message("$name is greeted in:\n$lines")
}
