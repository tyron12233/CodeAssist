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
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange

/**
 * The Java [BlockMapping]. It decomposes statements and key expressions — declarations
 * (class/method/field/local/param/import/package), control-flow statements
 * (if/for/while/do/return/throw/try/switch + expression statements), method calls, name/member
 * references, binary (`InfixExpression`) expressions, type refs, and literals. Anything else collapses to
 * an editable text slot via the engine's opaque fallback (e.g. lambdas, ternaries, `new`, casts) — still
 * editable, and it explodes back into blocks once it parses.
 *
 * Most kinds use the engine's generic gap-carving ([ProjectionContext.carve]); the three containers
 * (compilation unit, class body, block body) override to a single multiple slot so their members /
 * statements form an insertable, foldable list rather than a chrome-separated row of single slots.
 *
 * The kind strings for control-flow statements (`IfStatement`, …) are the names the JDT DOM emits for
 * nodes without a neutral [NodeKind] constant (see lang-jdt's `JdtDom.kindOf`). This is a coupling via
 * strings only — this module never links JDT.
 */
object JavaBlockMapping : BlockMapping {

    override val languages: Set<LanguageId> = setOf(LanguageId("java"))

    override val handles: Set<NodeKind> = buildSet {
        // declarations
        add(NodeKind.COMPILATION_UNIT); add(NodeKind.PACKAGE_DECL); add(NodeKind.IMPORT_DECL)
        add(NodeKind.CLASS_DECL); add(NodeKind.METHOD_DECL); add(NodeKind.FIELD_DECL)
        add(NodeKind.PARAMETER); add(NodeKind.LOCAL_VAR)
        // statements
        add(NodeKind.BLOCK)
        addAll(STATEMENT_KINDS)
        // key expressions
        add(NodeKind.METHOD_CALL); add(NodeKind.NAME_REF); add(NodeKind.MEMBER_ACCESS)
        add(NodeKind.TYPE_REF); add(NodeKind.LITERAL); add(INFIX_EXPRESSION)
    }

    override fun project(node: DomNode, ctx: ProjectionContext): BlockNode = when (node.kind) {
        // A method/initializer body: one statement list (foldable, insertable).
        NodeKind.BLOCK -> container(node, ctx, isBody = { true }, bodyCategory = SlotCategory.STATEMENT)
        // A class body: header (modifiers, name, supertypes) then a member list.
        NodeKind.CLASS_DECL -> container(node, ctx, isBody = { categoryFor(it.kind) == SlotCategory.DECLARATION }, bodyCategory = SlotCategory.DECLARATION)
        // The whole file: package + imports + types as one top-level declaration list.
        NodeKind.COMPILATION_UNIT -> container(node, ctx, isBody = { true }, bodyCategory = SlotCategory.DECLARATION)
        // A call: collapse the pure-name qualifier into the header and flatten fluent chains.
        NodeKind.METHOD_CALL -> methodCall(node, ctx)
        // A bare qualified name (`System.out`): ONE editable token, not nested access/name fragments.
        NodeKind.MEMBER_ACCESS -> if (isPureName(node)) pureName(node, ctx) else ctx.carve(node)
        else -> ctx.carve(node)
    }

    override fun template(): BlockTemplate =
        BlockTemplate(label = "statement", category = SlotCategory.STATEMENT, defaultText = "${BlockTemplate.PLACEHOLDER};")

    /**
     * Project [node] as a container: carve its header children (everything before the body region) as
     * single slots, then place the contiguous run of body children into one multiple slot of
     * [bodyCategory], with the surrounding `{`/`}` (and inter-member text) as chrome. Falls back to generic
     * carve when there is no body (e.g. an empty block, an abstract method) so nothing is lost.
     */
    private fun container(node: DomNode, ctx: ProjectionContext, isBody: (DomNode) -> Boolean, bodyCategory: SlotCategory): BlockNode {
        val children = node.children
        val body = children.filter(isBody)
        if (body.isEmpty()) return ctx.carve(node)
        val firstBody = body.first().range.start
        val lastBody = body.last().range.end
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in children) {
            if (c.range.start >= firstBody) break // header children only
            if (c.range.start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, c.range.start)))
            parts += BlockPart.Slot(ctx.slot(categoryFor(c.kind), listOf(ctx.child(c)), multiple = false, range = c.range))
            pos = c.range.end
        }
        if (firstBody > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, firstBody)))
        parts += BlockPart.Slot(ctx.slot(bodyCategory, body.map { ctx.child(it) }, multiple = true, range = TextRange(firstBody, lastBody)))
        if (node.range.end > lastBody) parts += BlockPart.Field(ctx.chromeField(TextRange(lastBody, node.range.end)))
        return ctx.block(node, node.kind, parts, labelFor(node.kind))
    }

    // ---- call-chain collapse (long-call readability) ----

    /** One flattened chain segment: the method/field name plus its arguments (a field link has none). */
    private class Link(val name: DomNode, val args: List<DomNode>)

    /**
     * Project a call as ONE block: a pure-name receiver (`System.out`) collapses to an editable
     * `qualifier` header field, and a fluent chain (`sb.append(x).append(y)`) flattens into left-to-right
     * segments — `name`/`name$i` fields with one ARGUMENT slot per argument. Parts are emitted by walking
     * the flattened pieces IN SOURCE ORDER with a cursor (like [container]), every gap (dots, parens,
     * commas) becoming a chrome field over its REAL source range — so serialization stays byte-for-byte.
     * Anything this shape can't represent (a missing name, type arguments) falls back to generic carve.
     */
    private fun methodCall(node: DomNode, ctx: ProjectionContext): BlockNode {
        // 1. Flatten the receiver chain, outermost call inward; collect links innermost-first.
        val links = ArrayList<Link>()
        var base: DomNode? = null
        var current: DomNode? = node
        while (current != null) {
            val cur = current
            when {
                cur.kind == NodeKind.METHOD_CALL -> {
                    val kids = cur.children
                    val nameIdx = methodNameIndex(cur) ?: return ctx.carve(node)
                    if (nameIdx > 1) return ctx.carve(node) // type arguments etc. — keep the generic carve
                    links += Link(kids[nameIdx], kids.subList(nameIdx + 1, kids.size))
                    current = if (nameIdx == 1) kids[0] else null
                }
                // `foo().bar.baz()` — a field hop in the chain becomes a link without parens.
                cur.kind == NodeKind.MEMBER_ACCESS && !isPureName(cur) &&
                    cur.children.size == 2 && cur.children[1].kind == NodeKind.NAME_REF -> {
                    links += Link(cur.children[1], emptyList())
                    current = cur.children[0]
                }
                else -> {
                    base = cur
                    current = null
                }
            }
        }
        links.reverse()

        // 2. Emit the parts in source order with a cursor; gaps become real-range chrome.
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        fun gapTo(start: Int) {
            if (start > pos) parts += BlockPart.Field(ctx.chromeField(TextRange(pos, start)))
        }
        base?.let { b ->
            gapTo(b.range.start)
            parts += if (isPureName(b)) {
                BlockPart.Field(ctx.field("qualifier", ctx.textOf(b.range).toString(), editable = true, range = b.range))
            } else {
                BlockPart.Slot(ctx.slot(SlotCategory.EXPRESSION, listOf(ctx.child(b)), multiple = false, range = b.range, valueKind = ctx.produced(b)))
            }
            pos = b.range.end
        }
        links.forEachIndexed { i, link ->
            gapTo(link.name.range.start)
            parts += BlockPart.Field(ctx.field(if (i == 0) "name" else "name$i", ctx.textOf(link.name.range).toString(), editable = true, range = link.name.range))
            pos = link.name.range.end
            for (arg in link.args) {
                gapTo(arg.range.start)
                parts += BlockPart.Slot(ctx.slot(SlotCategory.ARGUMENT, listOf(ctx.child(arg)), multiple = false, range = arg.range, valueKind = ctx.produced(arg)))
                pos = arg.range.end
            }
        }
        gapTo(node.range.end)
        return ctx.block(node, node.kind, parts, labelFor(node.kind), valueKind = ctx.produced(node))
    }

    /** A pure dotted name (`System.out`) as one block with a single editable `name` token. */
    private fun pureName(node: DomNode, ctx: ProjectionContext): BlockNode =
        ctx.block(
            node, node.kind,
            listOf(BlockPart.Field(ctx.field("name", ctx.textOf(node.range).toString(), editable = true, range = node.range))),
            labelFor(node.kind), valueKind = ctx.produced(node),
        )

    /** Whether [node] is just a (possibly dotted) name — only name_ref/member_access in its subtree. */
    private fun isPureName(node: DomNode): Boolean = when (node.kind) {
        NodeKind.NAME_REF -> true
        NodeKind.MEMBER_ACCESS -> node.children.all { isPureName(it) }
        else -> false
    }

    /**
     * The index of [call]'s method-name child: the name_ref whose following source gap (up to the next
     * child or the node end) opens the argument list — i.e. contains `(`. Null when recovery left none.
     */
    private fun methodNameIndex(call: DomNode): Int? {
        val kids = call.children
        val text = call.text()
        val origin = call.range.start
        for (i in kids.indices) {
            if (kids[i].kind != NodeKind.NAME_REF) continue
            val gapStart = (kids[i].range.end - origin).coerceIn(0, text.length)
            val gapEnd = ((kids.getOrNull(i + 1)?.range?.start ?: call.range.end) - origin).coerceIn(gapStart, text.length)
            if ('(' in text.subSequence(gapStart, gapEnd)) return i
        }
        return null
    }
}

/** Build a read-only chrome field over [range]'s source via the [ProjectionContext] factories. */
private fun ProjectionContext.chromeField(range: TextRange) =
    field(role = "syntax", text = textOf(range).toString(), editable = false, range = range)

/** Produced-kind lookup: the engine's pass resolves it (oracle first); plain contexts fall back to the syntactic heuristic. */
private fun ProjectionContext.produced(node: DomNode): ValueKind =
    (this as? ValueKindResolver)?.produced(node) ?: valueKindFor(node)

// ---------------------------------------------------------------------------
// Kind classification (JDT-DOM-aware heuristics, shared by the engine + mapping).
// These read only neutral NodeKind ids — string suffixes for the kinds JDT emits without a constant.
// ---------------------------------------------------------------------------

internal val INFIX_EXPRESSION = NodeKind("InfixExpression")

/** Control-flow / statement kinds the JDT DOM emits as class-name kinds (no neutral constant). */
internal val STATEMENT_KINDS: Set<NodeKind> = setOf(
    "IfStatement", "ForStatement", "EnhancedForStatement", "WhileStatement", "DoStatement",
    "ReturnStatement", "ExpressionStatement", "ThrowStatement", "TryStatement", "SwitchStatement",
    "SynchronizedStatement", "BreakStatement", "ContinueStatement", "AssertStatement", "YieldStatement",
    "LabeledStatement", "EmptyStatement",
).map { NodeKind(it) }.toSet()

/** The slot category a child of [kind] satisfies — what may be placed where (placement validity). */
internal fun categoryFor(kind: NodeKind): SlotCategory {
    val id = kind.id
    return when {
        kind == NodeKind.BLOCK || kind == NodeKind.LOCAL_VAR -> SlotCategory.STATEMENT
        kind in STATEMENT_KINDS || id.endsWith("Statement") -> SlotCategory.STATEMENT
        kind == NodeKind.TYPE_REF || id.endsWith("Type") -> SlotCategory.TYPE
        kind == NodeKind.PARAMETER -> SlotCategory.PARAMETER
        kind == NodeKind.METHOD_DECL || kind == NodeKind.FIELD_DECL || kind == NodeKind.CLASS_DECL -> SlotCategory.DECLARATION
        kind == NodeKind.IMPORT_DECL || kind == NodeKind.PACKAGE_DECL -> SlotCategory.DECLARATION
        kind == NodeKind.METHOD_CALL || kind == NodeKind.MEMBER_ACCESS || kind == NodeKind.NAME_REF -> SlotCategory.EXPRESSION
        kind == NodeKind.LITERAL || id.endsWith("Expression") || id.endsWith("Literal") || id.endsWith("Access") -> SlotCategory.EXPRESSION
        else -> SlotCategory.OPAQUE
    }
}

/** The field role for a leaf block's editable token. */
internal fun roleFor(kind: NodeKind): String = when {
    kind == NodeKind.NAME_REF -> "name"
    kind == NodeKind.LITERAL || kind.id.endsWith("Literal") -> "literal"
    kind == NodeKind.TYPE_REF || kind.id.endsWith("Type") -> "type"
    else -> "code"
}

// ---------------------------------------------------------------------------
// Syntactic ValueKind inference (the shape language). Pure functions of the
// DOM/text — ids must stay deterministic per text (computeEdit re-projects the same buffer), so
// no caching and no global state. A ValueKindOracle can refine these semantically.
// ---------------------------------------------------------------------------

/** A type node: the neutral TYPE_REF constant or a JDT class-name kind (`PrimitiveType`, `ArrayType`…). */
private fun isTypeNode(node: DomNode): Boolean =
    node.kind == NodeKind.TYPE_REF || node.kind.id.endsWith("Type")

/** The [ValueKind] [node] PRODUCES as an expression — UNKNOWN for statements and unresolved refs. */
internal fun valueKindFor(node: DomNode): ValueKind {
    val id = node.kind.id
    return when {
        // ALL literal classes map to the one LITERAL kind — distinguish by token text.
        node.kind == NodeKind.LITERAL -> literalKind(node.text().toString())
        id == "TextBlock" -> ValueKind.STRING
        isTypeNode(node) -> ValueKind.TYPE
        id == "InfixExpression" -> infixKind(node)
        id == "PrefixExpression" -> when (node.text().firstOrNull()) {
            '!' -> ValueKind.BOOLEAN
            '-', '+', '~' -> ValueKind.NUMBER
            else -> ValueKind.UNKNOWN
        }
        // `c ? a : b` produces what its then-branch does (children = [condition, then, else]).
        id == "ConditionalExpression" -> node.children.getOrNull(1)?.let { valueKindFor(it) } ?: ValueKind.UNKNOWN
        id == "ParenthesizedExpression" -> node.children.singleOrNull()?.let { valueKindFor(it) } ?: ValueKind.UNKNOWN
        id == "CastExpression" -> node.children.firstOrNull { isTypeNode(it) }?.let { primitiveKind(it.text().toString()) } ?: ValueKind.UNKNOWN
        id == "ClassInstanceCreation" -> ValueKind.OBJECT
        // method_call / member_access / name_ref need bindings — the oracle's job, later.
        else -> ValueKind.UNKNOWN
    }
}

/** A literal token's kind: chars read as text (the shape language), `null` is an object reference. */
private fun literalKind(token: String): ValueKind = when {
    token.startsWith("\"") || token.startsWith("'") -> ValueKind.STRING
    token == "true" || token == "false" -> ValueKind.BOOLEAN
    token == "null" -> ValueKind.OBJECT
    else -> ValueKind.NUMBER
}

/** An infix expression's kind, from the operator in the source gap between its first two operands. */
private fun infixKind(node: DomNode): ValueKind {
    val kids = node.children
    if (kids.size < 2) return ValueKind.UNKNOWN
    val text = node.text()
    val gapStart = (kids[0].range.end - node.range.start).coerceIn(0, text.length)
    val gapEnd = (kids[1].range.start - node.range.start).coerceIn(gapStart, text.length)
    return when (text.subSequence(gapStart, gapEnd).trim().toString()) {
        "&&", "||", "<", ">", "<=", ">=", "==", "!=", "instanceof" -> ValueKind.BOOLEAN
        // `+` concatenates when ANY operand (incl. extended operands, `1 + 2 + "x"`) is a string.
        "+" -> if (kids.any { valueKindFor(it) == ValueKind.STRING }) ValueKind.STRING else ValueKind.NUMBER
        "-", "*", "/", "%", "^", "&", "|", "<<", ">>", ">>>" -> ValueKind.NUMBER
        else -> ValueKind.UNKNOWN
    }
}

/** Parent kinds whose (first) expression child is a condition — the slot expects a boolean. */
private val CONDITION_PARENTS = setOf("IfStatement", "WhileStatement", "DoStatement", "AssertStatement")

/**
 * The [ValueKind] the POSITION of [child] under [parent] expects (an `if` condition expects BOOLEAN, an
 * `int x = …` initializer expects NUMBER) — UNKNOWN when the position itself gives no expectation.
 * Nodes are matched by RANGE, not identity (DOM wrappers are rebuilt on every `children` access).
 */
internal fun expectedValueKind(parent: DomNode, child: DomNode): ValueKind {
    val pid = parent.kind.id
    return when {
        // Plain ForStatement is skipped: its condition is positionally ambiguous among init/cond/update.
        pid in CONDITION_PARENTS ->
            if (firstExpressionChild(parent)?.range == child.range) ValueKind.BOOLEAN else ValueKind.UNKNOWN
        pid == "ConditionalExpression" ->
            if (parent.children.firstOrNull()?.range == child.range) ValueKind.BOOLEAN else ValueKind.UNKNOWN
        // `int x = 1;` — JDT splits into local_var(statement){type, local_var(fragment){name_ref, init}}:
        // the initializer is the EXPRESSION child after the name; the type sits on the parent or its parent.
        parent.kind == NodeKind.LOCAL_VAR || parent.kind == NodeKind.FIELD_DECL -> {
            if (isInitializerChild(parent, child)) declaredKind(parent) else ValueKind.UNKNOWN
        }
        pid == "ReturnStatement" -> enclosingMethodReturnKind(parent)
        else -> ValueKind.UNKNOWN
    }
}

private fun firstExpressionChild(parent: DomNode): DomNode? =
    parent.children.firstOrNull { categoryFor(it.kind) == SlotCategory.EXPRESSION }

/** The initializer of a declaration: an EXPRESSION-category child appearing AFTER a name_ref child. */
private fun isInitializerChild(parent: DomNode, child: DomNode): Boolean {
    if (categoryFor(child.kind) != SlotCategory.EXPRESSION) return false
    return parent.children.any { it.kind == NodeKind.NAME_REF && it.range.end <= child.range.start }
}

/** The declared type of a fragment's initializer: the nearest type child of the parent or its parent. */
private fun declaredKind(parent: DomNode): ValueKind {
    val type = parent.children.firstOrNull(::isTypeNode) ?: parent.parent?.children?.firstOrNull(::isTypeNode)
    return type?.let { primitiveKind(it.text().toString()) } ?: ValueKind.UNKNOWN
}

/** What a `return` expression should produce: the enclosing method's declared return type. */
private fun enclosingMethodReturnKind(node: DomNode): ValueKind {
    var p: DomNode? = node.parent
    while (p != null && p.kind != NodeKind.METHOD_DECL) p = p.parent
    val type = p?.children?.firstOrNull(::isTypeNode) ?: return ValueKind.UNKNOWN
    return primitiveKind(type.text().toString())
}

/** The [ValueKind] a declared type text denotes — chars read as text; arrays/generics are objects. */
internal fun primitiveKind(typeText: String): ValueKind {
    val t = typeText.trim()
    if ('[' in t) return ValueKind.OBJECT // any array is a reference, whatever its element type
    return when (t.substringBefore('<').trim()) {
        "boolean", "Boolean" -> ValueKind.BOOLEAN
        "int", "long", "short", "byte", "float", "double",
        "Integer", "Long", "Short", "Byte", "Float", "Double" -> ValueKind.NUMBER
        "char", "Character", "String", "CharSequence" -> ValueKind.STRING
        "void" -> ValueKind.UNKNOWN
        else -> ValueKind.OBJECT
    }
}

/** A short header label for a block (the chip the UI shows). Empty = render transparently. */
internal fun labelFor(kind: NodeKind): String = when (kind) {
    NodeKind.COMPILATION_UNIT -> "file"
    NodeKind.PACKAGE_DECL -> "package"
    NodeKind.IMPORT_DECL -> "import"
    NodeKind.CLASS_DECL -> "class"
    NodeKind.METHOD_DECL -> "method"
    NodeKind.FIELD_DECL -> "field"
    NodeKind.LOCAL_VAR -> "var"
    NodeKind.PARAMETER -> "param"
    NodeKind.BLOCK -> "block"
    NodeKind.METHOD_CALL -> "call"
    NodeKind.MEMBER_ACCESS -> "access"
    NodeKind.NAME_REF -> "name"
    NodeKind.TYPE_REF -> "type"
    NodeKind.LITERAL -> "value"
    INFIX_EXPRESSION -> "expr"
    else -> when (kind.id) {
        "IfStatement" -> "if"
        "ForStatement", "EnhancedForStatement" -> "for"
        "WhileStatement" -> "while"
        "DoStatement" -> "do"
        "ReturnStatement" -> "return"
        "ThrowStatement" -> "throw"
        "TryStatement" -> "try"
        "SwitchStatement" -> "switch"
        "ExpressionStatement" -> "" // transparent — just shows its inner call/assignment
        else -> kind.id
    }
}
