package dev.ide.core.backend

import dev.ide.ui.backend.UiExerciseResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Grade an Android layout exercise: [xml] must be well-formed, every [LayoutCheck.views] rule must match,
 * and (with [LayoutCheck.cleanRender]) the owned layout engine must render it without a problem.
 *
 * The structure is read from the XML itself rather than from the render tree, because the rules are about
 * what the learner wrote (a `Button` with id `signIn` inside a vertical `LinearLayout`), and the source
 * keeps tag names, ids and attribute spellings exactly. The render is the second half: it catches a layout
 * that has the right shape but would not inflate. [render] returns the engine's problems, or null when it
 * could not render at all.
 *
 * The result's output lists every rule with a tick or a cross, so the learner sees what is still missing
 * rather than one failure at a time.
 */
internal fun checkLayout(xml: String, check: LayoutCheck, render: (String) -> List<String>?): UiExerciseResult {
    val root = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities in a lesson buffer.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8)).documentElement
    } catch (e: Exception) {
        val why = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return UiExerciseResult(
            passed = false,
            compiled = false,
            message = "This isn't well-formed XML yet. Check that every tag is closed and every attribute is quoted.",
            diagnostics = listOfNotNull(why.ifBlank { null }),
        )
    }

    val elements = ArrayList<Element>()
    fun walk(n: Node) {
        if (n is Element) elements += n
        val kids = n.childNodes
        for (i in 0 until kids.length) walk(kids.item(i))
    }
    walk(root)

    val lines = ArrayList<String>()
    val unmet = ArrayList<String>()
    for (rule in check.views) {
        val found = elements.count { matches(it, rule) }
        val ok = found >= rule.count
        lines += (if (ok) "✓ " else "✗ ") + rule.label
        if (!ok) unmet += rule.label
    }

    val problems = if (check.cleanRender) render(xml) else emptyList()
    val renderOk = !check.cleanRender || (problems != null && problems.isEmpty())
    if (check.cleanRender) lines += if (renderOk) "✓ renders cleanly" else "✗ renders cleanly"

    val passed = unmet.isEmpty() && renderOk
    val message = when {
        passed -> "Correct, nicely done!"
        unmet.isNotEmpty() -> "Almost. Your layout still needs: " + unmet.joinToString("; ") + "."
        problems == null -> "The structure is right, but the layout couldn't be rendered."
        else -> "The structure is right, but the preview reports a problem (below)."
    }
    return UiExerciseResult(
        passed = passed,
        compiled = true,
        output = lines.joinToString("\n"),
        message = message,
        diagnostics = problems.orEmpty(),
    )
}

private fun matches(el: Element, rule: ViewRule): Boolean {
    if (!tagMatches(el.tagName, rule.tag)) return false
    if (rule.id != null && idName(el.getAttribute("android:id")) != rule.id) return false
    if (rule.parent != null) {
        val parent = el.parentNode as? Element ?: return false
        if (!tagMatches(parent.tagName, rule.parent)) return false
    }
    return rule.attrs.all { (name, value) -> el.hasAttribute(name) && el.getAttribute(name).trim() == value }
}

/** `Button` matches `Button` and any qualified `....Button`; a qualified rule must match exactly. */
private fun tagMatches(tag: String, want: String): Boolean =
    tag == want || (!want.contains('.') && tag.substringAfterLast('.') == want)

/** `@+id/signIn` and `@id/signIn` → `signIn`. */
private fun idName(raw: String): String = raw.trim().substringAfter('/', "")
