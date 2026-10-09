package dev.ide.block.impl

import dev.ide.block.BlockMapping
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockTemplate
import dev.ide.block.ProjectionContext
import dev.ide.block.SlotCategory
import dev.ide.block.ValueKind
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.KotlinNodeKinds as K
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange

/**
 * The Kotlin [BlockMapping], over the Kotlin neutral DOM ([K]).
 *
 * Kotlin differs from Java in ways the block view feels directly. A body's statements are bare expressions
 * (no statement wrapper), `if`/`when`/`try` are expressions that also act as statements, control bodies may
 * be brace-less (`if (x) foo()`), and a trailing lambda (`Column { … }`, `items.forEach { … }`) is how most
 * Kotlin and all Compose code nests. So this mapping:
 *
 *  - unwraps the DOM's condition and control-body containers, so `if`/`while`/`for` carry their condition
 *    and their bodies directly as slots (a body slot is STATEMENT, which the view draws as a mouth);
 *  - projects `when` as its subject plus one clause per entry (conditions, then the branch body);
 *  - collapses calls and qualified chains into one block like the Java mapping does, and turns a trailing
 *    lambda into a body slot after the arguments, so a call with a lambda is a C-block;
 *  - marks the declared name of a `val`/`var`/`fun`/`class` as a `declname` field (read-only: renaming is a
 *    refactoring, not a token edit) and the `val`/`var` keyword as `keyword`, so the view can show them.
 *
 * Anything not handled here (lambdas passed as plain arguments, `is` checks, object literals) collapses to an
 * editable text block through the engine's opaque fallback.
 */
object KotlinBlockMapping : BlockMapping {

    override val languages: Set<LanguageId> = setOf(LanguageId("kotlin"))

    override val handles: Set<NodeKind> = setOf(
        K.COMPILATION_UNIT, K.PACKAGE_DECL, K.IMPORT_LIST, K.IMPORT_DECL, K.CLASS_DECL, K.CLASS_BODY,
        K.METHOD_DECL, K.PROPERTY, K.PROPERTY_ACCESSOR, K.INIT, K.CONSTRUCTOR, K.LOCAL_VAR, K.PARAMETER,
        K.BLOCK, K.METHOD_CALL, K.MEMBER_ACCESS, K.SAFE_ACCESS, K.NAME_REF, K.TYPE_REF, K.LITERAL, K.STRING_TEMPLATE,
        K.BINARY, K.PREFIX, K.POSTFIX, K.PARENTHESIZED, K.THIS,
        K.IF, K.WHEN, K.WHEN_ENTRY, K.FOR, K.WHILE, K.DO_WHILE, K.TRY, K.CATCH, K.FINALLY,
        K.RETURN, K.THROW, K.BREAK, K.CONTINUE,
    )

    override fun project(node: DomNode, ctx: ProjectionContext): BlockNode = when (node.kind) {
        K.COMPILATION_UNIT -> container(node, ctx, SlotCategory.DECLARATION)
        K.CLASS_BODY -> container(node, ctx, SlotCategory.DECLARATION)
        K.BLOCK -> container(node, ctx, SlotCategory.STATEMENT)
        K.IMPORT_LIST -> generic(node, ctx, label = "imports")
        K.TYPE_REF, K.STRING_TEMPLATE, K.THIS, K.BREAK, K.CONTINUE -> leaf(node, ctx)
        K.LOCAL_VAR, K.PROPERTY -> declaration(node, ctx, keywords = setOf("val", "var"))
        K.METHOD_DECL -> declaration(node, ctx, keywords = setOf("fun"))
        K.CLASS_DECL -> declaration(node, ctx, keywords = setOf("class", "interface", "object"))
        K.METHOD_CALL, K.MEMBER_ACCESS, K.SAFE_ACCESS -> call(node, ctx)
        K.IF -> control(node, ctx, "if")
        K.WHILE -> control(node, ctx, "while")
        K.DO_WHILE -> control(node, ctx, "do")
        K.FOR -> control(node, ctx, "for")
        K.WHEN -> whenBlock(node, ctx)
        K.WHEN_ENTRY -> whenEntry(node, ctx)
        else -> generic(node, ctx)
    }

    override fun template(): BlockTemplate =
        BlockTemplate(label = "statement", category = SlotCategory.STATEMENT, defaultText = BlockTemplate.PLACEHOLDER.toString())

    // ---- the generic carve, Kotlin-flavoured ----

    /**
     * Interleave source chrome with child slots, like the engine's carve, but with Kotlin's quirks: operator
     * tokens are chrome (not opaque blocks), and the DOM's condition / control-body containers are skipped
     * so their content sits in the slot directly.
     */
    private fun generic(node: DomNode, ctx: ProjectionContext, label: String? = null): BlockNode {
        // A bare name or literal is an editable token; any other childless node (`return`) stays chrome.
        if (node.children.isEmpty() && (node.kind == K.NAME_REF || node.kind == K.LITERAL)) return leaf(node, ctx)
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in node.children) {
            if (c.kind == K.OPERATOR || c.kind == K.MODIFIER_LIST) continue
            val content = unwrap(c)
            if (content.range.start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, content.range.start)))
            parts += BlockPart.Slot(slotFor(node, c, content, ctx))
            pos = content.range.end
        }
        if (node.range.end > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, node.range.end)))
        return ctx.block(node, node.kind, parts, label ?: KotlinDialect.labelFor(node.kind), valueKind = ctx.produced(node, KotlinDialect))
    }

    /** The slot for child [c] of [parent] (whose [content] is [c] itself or what its container wraps). */
    private fun slotFor(parent: DomNode, c: DomNode, content: DomNode, ctx: ProjectionContext) = when {
        // A control body (then/else/loop body, a when branch, a catch block) is a statement position.
        c.kind == K.CONTROL_BODY || (content.kind == K.BLOCK && parent.kind != K.METHOD_DECL) ->
            ctx.slot(SlotCategory.STATEMENT, listOf(ctx.child(content)), multiple = false, range = content.range)
        // A function's own body block is its statement list; an expression body (`= x`) a value.
        else -> {
            val expected = KotlinDialect.expectedValueKind(parent, c).takeIf { it != ValueKind.UNKNOWN } ?: ctx.produced(content, KotlinDialect)
            ctx.slot(KotlinDialect.categoryFor(content.kind), listOf(ctx.child(content)), multiple = false, range = content.range, valueKind = expected)
        }
    }

    /** What a DOM container wraps: a condition's expression, a control body's block or bare statement. */
    private fun unwrap(c: DomNode): DomNode =
        if ((c.kind == K.CONTAINER || c.kind == K.CONTROL_BODY) && c.children.size == 1) c.children[0] else c

    private fun leaf(node: DomNode, ctx: ProjectionContext): BlockNode {
        val role = if (node.kind == K.BREAK || node.kind == K.CONTINUE) "keyword" else KotlinDialect.roleFor(node.kind)
        val editable = role != "keyword"
        val field = ctx.field(role, ctx.textOf(node.range).toString(), editable = editable, range = node.range)
        return ctx.block(node, node.kind, listOf(BlockPart.Field(field)), KotlinDialect.labelFor(node.kind), valueKind = ctx.produced(node, KotlinDialect))
    }

    // ---- containers ----

    /**
     * A body as ONE list slot of [category], with the braces and gaps as chrome. An empty `{}` keeps an
     * empty slot just inside the brace; a lambda's body (whose range excludes the braces) keeps one at its
     * start. Header children (none for a block) are carved as single slots first.
     */
    private fun container(node: DomNode, ctx: ProjectionContext, category: SlotCategory): BlockNode {
        val body = node.children
        val parts = ArrayList<BlockPart>()
        if (body.isEmpty()) {
            val text = ctx.textOf(node.range)
            val brace = text.indexOf('{')
            val anchor = if (brace >= 0 && text.subSequence(0, brace).isBlank()) node.range.start + brace + 1 else node.range.start
            if (anchor > node.range.start) parts += BlockPart.Field(ctx.chromeField(TextRange(node.range.start, anchor)))
            parts += BlockPart.Slot(ctx.slot(category, emptyList(), multiple = true, range = TextRange(anchor, anchor)))
            if (node.range.end > anchor) parts += BlockPart.Field(ctx.chromeField(TextRange(anchor, node.range.end)))
            return ctx.block(node, node.kind, parts, KotlinDialect.labelFor(node.kind))
        }
        val first = body.first().range.start
        val last = body.last().range.end
        if (first > node.range.start) parts += BlockPart.Field(ctx.chromeField(TextRange(node.range.start, first)))
        parts += BlockPart.Slot(ctx.slot(category, body.map { ctx.child(it) }, multiple = true, range = TextRange(first, last)))
        if (node.range.end > last) parts += BlockPart.Field(ctx.chromeField(TextRange(last, node.range.end)))
        return ctx.block(node, node.kind, parts, KotlinDialect.labelFor(node.kind))
    }

    // ---- declarations ----

    /**
     * A `val`/`var`/`fun`/`class` declaration: carved like [generic], but the keyword and the declared name
     * (which the DOM does not give a node of its own) become `keyword` and `declname` fields over their real
     * source, so the view can show them without guessing at chrome.
     */
    private fun declaration(node: DomNode, ctx: ProjectionContext, keywords: Set<String>): BlockNode {
        val base = generic(node, ctx)
        val text = ctx.source
        val kw = Regex("\\b(" + keywords.joinToString("|") + ")\\b")
        val split = ArrayList<BlockPart>()
        var done = false
        for (part in base.parts) {
            val f = (part as? BlockPart.Field)?.field
            val r = f?.range
            val m = if (!done && f != null && !f.editable && r != null) kw.find(f.text) else null
            if (m == null || r == null) { split += part; continue }
            done = true
            val kwStart = r.start + m.range.first
            val kwEnd = r.start + m.range.last + 1
            // The name: the identifier after the keyword (skipping a `<T>` and a `Receiver.`), within this gap.
            val after = text.subSequence(kwEnd, r.end).toString()
            val nm = Regex("^\\s*(?:<[^>]*>\\s*)?(?:[\\w.<>?, ]+\\.)?`?([A-Za-z_][\\w]*)`?").find(after)
            if (kwStart > r.start) split += BlockPart.Field(ctx.chromeField(TextRange(r.start, kwStart)))
            split += BlockPart.Field(ctx.field("keyword", text.subSequence(kwStart, kwEnd).toString(), editable = false, range = TextRange(kwStart, kwEnd)))
            if (nm != null) {
                val g = nm.groups[1]!!
                val ns = kwEnd + g.range.first
                val ne = kwEnd + g.range.last + 1
                if (ns > kwEnd) split += BlockPart.Field(ctx.chromeField(TextRange(kwEnd, ns)))
                split += BlockPart.Field(ctx.field("declname", g.value, editable = false, range = TextRange(ns, ne)))
                if (r.end > ne) split += BlockPart.Field(ctx.chromeField(TextRange(ne, r.end)))
            } else if (r.end > kwEnd) split += BlockPart.Field(ctx.chromeField(TextRange(kwEnd, r.end)))
        }
        return ctx.block(node, node.kind, split, base.label, valueKind = base.valueKind)
    }

    // ---- control flow ----

    private fun control(node: DomNode, ctx: ProjectionContext, label: String): BlockNode = generic(node, ctx, label)

    /** `when (x) { … }`: the subject as a value slot, each entry as a clause slot. */
    private fun whenBlock(node: DomNode, ctx: ProjectionContext): BlockNode {
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in node.children) {
            if (c.range.start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, c.range.start)))
            val category = if (c.kind == K.WHEN_ENTRY) SlotCategory.DECLARATION else SlotCategory.EXPRESSION
            parts += BlockPart.Slot(ctx.slot(category, listOf(ctx.child(c)), multiple = false, range = c.range, valueKind = ctx.produced(c, KotlinDialect)))
            pos = c.range.end
        }
        if (node.range.end > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, node.range.end)))
        return ctx.block(node, node.kind, parts, "when", valueKind = ctx.produced(node, KotlinDialect))
    }

    /** `1, 2 -> body`: each condition's value as a slot, then the branch body as a statement slot. */
    private fun whenEntry(node: DomNode, ctx: ProjectionContext): BlockNode {
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        val kids = node.children
        kids.forEachIndexed { i, c ->
            val isBody = i == kids.lastIndex && c.kind != K.WHEN_CONDITION && c.kind != K.WHEN_CONDITION_IS && c.kind != K.WHEN_CONDITION_IN
            val content = if (c.kind == K.WHEN_CONDITION && c.children.size == 1) c.children[0] else c
            if (content.range.start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, content.range.start)))
            parts += BlockPart.Slot(
                if (isBody) ctx.slot(SlotCategory.STATEMENT, listOf(ctx.child(content)), multiple = false, range = content.range)
                else ctx.slot(SlotCategory.EXPRESSION, listOf(ctx.child(content)), multiple = false, range = content.range, valueKind = ctx.produced(content, KotlinDialect)),
            )
            pos = content.range.end
        }
        if (node.range.end > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, node.range.end)))
        return ctx.block(node, node.kind, parts, "case")
    }

    // ---- calls ----

    /** One flattened chain segment: a call (name, value arguments, trailing lambda) or a property hop. */
    private class Link(val name: DomNode, val args: List<DomNode>, val lambda: DomNode?, val argList: DomNode? = null)

    /**
     * A call or qualified chain as ONE block, like the Java mapping's collapse: a pure-name receiver becomes
     * an editable `qualifier`, each segment a `name`/`name<i>` field with one ARGUMENT slot per value
     * argument, and a trailing lambda a STATEMENT slot holding its body (so `Column { … }` is a C-block).
     * Gaps are chrome over their real source, so serialization stays byte-for-byte. A shape this cannot
     * represent falls back to [generic].
     */
    private fun call(node: DomNode, ctx: ProjectionContext): BlockNode {
        if (isPureName(node)) {
            return ctx.block(
                node, node.kind,
                listOf(BlockPart.Field(ctx.field("name", ctx.textOf(node.range).toString(), editable = true, range = node.range))),
                "access", valueKind = ctx.produced(node, KotlinDialect),
            )
        }
        val links = ArrayList<Link>()
        var base: DomNode? = null
        var cur: DomNode? = node
        while (cur != null) {
            val c = cur
            when (c.kind) {
                K.METHOD_CALL -> { links += callLink(c) ?: return generic(node, ctx); cur = null }
                K.MEMBER_ACCESS, K.SAFE_ACCESS -> {
                    if (isPureName(c)) { base = c; cur = null; continue }
                    val kids = c.children
                    if (kids.size != 2) return generic(node, ctx)
                    val sel = kids[1]
                    links += when (sel.kind) {
                        K.METHOD_CALL -> callLink(sel) ?: return generic(node, ctx)
                        K.NAME_REF -> Link(sel, emptyList(), null)
                        else -> return generic(node, ctx)
                    }
                    cur = kids[0]
                }
                else -> { base = c; cur = null }
            }
        }
        links.reverse()
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        fun gapTo(start: Int) { if (start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, start))) }
        base?.let { b ->
            gapTo(b.range.start)
            parts += if (isPureName(b)) BlockPart.Field(ctx.field("qualifier", ctx.textOf(b.range).toString(), editable = true, range = b.range))
            else BlockPart.Slot(ctx.slot(SlotCategory.EXPRESSION, listOf(ctx.child(b)), multiple = false, range = b.range, valueKind = ctx.produced(b, KotlinDialect)))
            pos = b.range.end
        }
        links.forEachIndexed { i, link ->
            gapTo(link.name.range.start)
            parts += BlockPart.Field(ctx.field(if (i == 0) "name" else "name$i", ctx.textOf(link.name.range).toString(), editable = true, range = link.name.range))
            pos = link.name.range.end
            for (arg in link.args) {
                gapTo(arg.range.start)
                parts += BlockPart.Slot(ctx.slot(SlotCategory.ARGUMENT, listOf(ctx.child(arg)), multiple = false, range = arg.range, valueKind = ctx.produced(arg, KotlinDialect)))
                pos = arg.range.end
            }
            // An empty `()` still has a place for its first argument.
            if (link.args.isEmpty()) link.argList?.let { list -> emptyArgumentAt(ctx.source, list.range.start, list.range.end) }?.let { at ->
                gapTo(at)
                parts += BlockPart.Slot(ctx.slot(SlotCategory.ARGUMENT, emptyList(), multiple = false, range = TextRange(at, at)))
                pos = at
            }
            link.lambda?.let { body ->
                gapTo(body.range.start)
                parts += BlockPart.Slot(ctx.slot(SlotCategory.STATEMENT, listOf(ctx.child(body)), multiple = false, range = body.range))
                pos = body.range.end
            }
        }
        gapTo(node.range.end)
        return ctx.block(node, K.METHOD_CALL, parts, "call", valueKind = ctx.produced(node, KotlinDialect))
    }

    /**
     * A call's segment: its name, the expressions of its value arguments (a named argument's `name =` stays
     * chrome), and the body block of a trailing lambda. Null when recovery left no name.
     */
    private fun callLink(call: DomNode): Link? {
        val kids = call.children
        val name = kids.firstOrNull()?.takeIf { it.kind == K.NAME_REF } ?: return null
        val args = kids.filter { it.kind == K.ARGUMENT_LIST }.flatMap { list ->
            list.children.filter { it.kind == K.ARGUMENT }.mapNotNull { a -> a.children.lastOrNull { it.kind != K.ARGUMENT_NAME } }
        }
        val lambda = kids.firstOrNull { it.kind == K.LAMBDA_ARGUMENT }?.let(::lambdaBody)
        if (kids.any { it.kind == K.LAMBDA_ARGUMENT } && lambda == null) return null
        return Link(name, args, lambda, kids.firstOrNull { it.kind == K.ARGUMENT_LIST })
    }

    /** The body block of a trailing lambda (`lambda_argument > lambda > function_literal > block`). */
    private fun lambdaBody(arg: DomNode): DomNode? {
        val literal = arg.children.singleOrNull { it.kind == K.LAMBDA }?.children?.singleOrNull { it.kind == K.FUNCTION_LITERAL } ?: return null
        return literal.children.lastOrNull { it.kind == K.BLOCK }
    }

    private fun isPureName(node: DomNode): Boolean = when (node.kind) {
        K.NAME_REF -> true
        K.MEMBER_ACCESS -> node.children.size == 2 && node.children.all(::isPureName)
        else -> false
    }
}

/** Kotlin's reading of its DOM kinds: labels the view keys off, slot categories, and value-kind guesses. */
internal object KotlinDialect : BlockDialect {

    override fun labelFor(kind: NodeKind): String = when (kind) {
        K.COMPILATION_UNIT -> "file"
        K.PACKAGE_DECL -> "package"
        K.IMPORT_DECL -> "import"
        K.IMPORT_LIST -> "imports"
        K.CLASS_DECL -> "class"
        K.CLASS_BODY -> "body"
        K.METHOD_DECL -> "method"
        K.PROPERTY -> "field"
        K.PROPERTY_ACCESSOR -> "accessor"
        K.INIT -> "init"
        K.CONSTRUCTOR -> "constructor"
        K.LOCAL_VAR -> "var"
        K.PARAMETER -> "param"
        K.BLOCK -> "block"
        K.METHOD_CALL -> "call"
        K.MEMBER_ACCESS, K.SAFE_ACCESS -> "access"
        K.NAME_REF -> "name"
        K.TYPE_REF -> "type"
        K.LITERAL, K.STRING_TEMPLATE -> "value"
        K.BINARY, K.PREFIX, K.POSTFIX -> "expr"
        K.PARENTHESIZED -> ""
        K.IF -> "if"
        K.WHEN -> "when"
        K.WHEN_ENTRY -> "case"
        K.FOR -> "for"
        K.WHILE -> "while"
        K.DO_WHILE -> "do"
        K.TRY -> "try"
        K.CATCH -> "catch"
        K.FINALLY -> "finally"
        K.RETURN -> "return"
        K.THROW -> "throw"
        K.BREAK -> "break"
        K.CONTINUE -> "continue"
        else -> kind.id
    }

    override fun categoryFor(kind: NodeKind): SlotCategory = when (kind) {
        K.BLOCK, K.LOCAL_VAR, K.FOR, K.WHILE, K.DO_WHILE, K.RETURN, K.THROW, K.BREAK, K.CONTINUE -> SlotCategory.STATEMENT
        K.TYPE_REF -> SlotCategory.TYPE
        K.PARAMETER, K.PARAMETER_LIST -> SlotCategory.PARAMETER
        K.METHOD_DECL, K.CLASS_DECL, K.PROPERTY, K.PROPERTY_ACCESSOR, K.INIT, K.CONSTRUCTOR, K.TYPEALIAS,
        K.PACKAGE_DECL, K.IMPORT_DECL, K.IMPORT_LIST, K.CLASS_BODY, K.CATCH, K.FINALLY -> SlotCategory.DECLARATION
        K.MODIFIER_LIST, K.ANNOTATION_ENTRY -> SlotCategory.MODIFIER
        // `if`/`when`/`try` are expressions in Kotlin: where they sit as a value, they are one.
        else -> SlotCategory.EXPRESSION
    }

    override fun roleFor(kind: NodeKind): String = when (kind) {
        K.NAME_REF -> "name"
        K.LITERAL, K.STRING_TEMPLATE -> "literal"
        K.TYPE_REF -> "type"
        else -> "code"
    }

    override fun valueKindFor(node: DomNode): ValueKind = when (node.kind) {
        K.STRING_TEMPLATE -> ValueKind.STRING
        K.LITERAL -> literalKind(node.text().toString().trim())
        K.TYPE_REF -> ValueKind.TYPE
        K.BINARY -> binaryKind(node)
        K.PREFIX -> when (node.text().firstOrNull()) {
            '!' -> ValueKind.BOOLEAN
            '-', '+' -> ValueKind.NUMBER
            else -> ValueKind.UNKNOWN
        }
        K.IS -> ValueKind.BOOLEAN
        K.PARENTHESIZED -> node.children.singleOrNull()?.let(::valueKindFor) ?: ValueKind.UNKNOWN
        // `if (c) a else b` as a value produces what its then-branch does.
        K.IF -> node.children.getOrNull(1)?.let { b -> (b.children.singleOrNull() ?: b).let(::valueKindFor) } ?: ValueKind.UNKNOWN
        else -> ValueKind.UNKNOWN
    }

    override fun expectedValueKind(parent: DomNode, child: DomNode): ValueKind = when (parent.kind) {
        // The first container of an `if`/`while` (and the last of a do-while) is the condition.
        K.IF, K.WHILE -> if (child.kind == K.CONTAINER && parent.children.firstOrNull { it.kind == K.CONTAINER }?.range == child.range) ValueKind.BOOLEAN else ValueKind.UNKNOWN
        K.DO_WHILE -> if (child.kind == K.CONTAINER) ValueKind.BOOLEAN else ValueKind.UNKNOWN
        K.LOCAL_VAR, K.PROPERTY -> {
            val type = parent.children.firstOrNull { it.kind == K.TYPE_REF }
            if (type != null && child.range.start > type.range.end && child.kind != K.PROPERTY_ACCESSOR) kotlinTypeKind(type.text().toString())
            else ValueKind.UNKNOWN
        }
        else -> ValueKind.UNKNOWN
    }

    private fun literalKind(token: String): ValueKind = when {
        token == "true" || token == "false" -> ValueKind.BOOLEAN
        token == "null" -> ValueKind.OBJECT
        token.startsWith("'") -> ValueKind.STRING
        else -> ValueKind.NUMBER
    }

    private fun binaryKind(node: DomNode): ValueKind {
        val op = node.children.firstOrNull { it.kind == K.OPERATOR }?.text()?.toString()?.trim() ?: return ValueKind.UNKNOWN
        return when (op) {
            "&&", "||", "<", ">", "<=", ">=", "==", "!=", "===", "!==", "in", "!in" -> ValueKind.BOOLEAN
            "+" -> if (node.children.any { valueKindFor(it) == ValueKind.STRING }) ValueKind.STRING else ValueKind.NUMBER
            "-", "*", "/", "%" -> ValueKind.NUMBER
            "..", "..<", "until", "downTo", "step" -> ValueKind.OBJECT
            else -> ValueKind.UNKNOWN
        }
    }

    /** The kind a declared Kotlin type denotes; a nullable type reads as its base. */
    fun kotlinTypeKind(typeText: String): ValueKind = when (typeText.trim().removeSuffix("?").substringBefore('<').trim()) {
        "Boolean" -> ValueKind.BOOLEAN
        "Int", "Long", "Short", "Byte", "Float", "Double", "UInt", "ULong", "Number" -> ValueKind.NUMBER
        "String", "Char", "CharSequence" -> ValueKind.STRING
        "Unit", "Nothing" -> ValueKind.UNKNOWN
        else -> ValueKind.OBJECT
    }
}
