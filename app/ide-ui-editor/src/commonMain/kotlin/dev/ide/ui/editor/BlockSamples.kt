package dev.ide.ui.editor

import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ===========================================================================
// Hand-built sample block trees for Compose previews and the render snapshots (the real projection needs
// JDT, which is JVM-only). The samples mirror what the Java projection produces, offsets lining up with the
// assembled source, so the previews exercise every block kind without launching the app.
// ===========================================================================

/**
 * Builds sample [UiBlockNode]s while assembling the backing source string, so each node's offsets line up
 * with the text — exactly what the renderer slices for types, sockets, signatures, and the package line.
 */
internal class BlockSample {
    val sb = StringBuilder()
    private var n = 0
    private fun id() = "s${n++}"

    fun chrome(t: String): UiBlockPart.Field { val s = sb.length; sb.append(t); return UiBlockPart.Field("syntax", t, false, s, sb.length) }
    fun field(role: String, t: String): UiBlockPart.Field { val s = sb.length; sb.append(t); return UiBlockPart.Field(role, t, true, s, sb.length) }
    fun gap() { sb.append("\n            ") }
    fun leaf(kind: String, label: String, role: String, t: String, valueKind: String = "unknown"): UiBlockNode {
        val s = sb.length; sb.append(t)
        return UiBlockNode(id(), kind, label, label, s, sb.length, listOf(UiBlockPart.Field(role, t, true, s, sb.length)), valueKind = valueKind)
    }
    fun build(kind: String, label: String, valueKind: String = "unknown", f: () -> List<UiBlockPart>): UiBlockNode {
        val s = sb.length; val parts = f()
        return UiBlockNode(id(), kind, label, label, s, sb.length, parts, valueKind = valueKind)
    }
    fun single(cat: String, child: UiBlockNode, valueKind: String = "unknown") =
        UiBlockPart.Slot(cat, false, child.start, child.end, listOf(child), valueKind = valueKind)
    fun empty(cat: String, valueKind: String = "unknown") =
        UiBlockPart.Slot(cat, false, sb.length, sb.length, emptyList(), valueKind = valueKind)
    fun list(cat: String, children: List<UiBlockNode>) =
        UiBlockPart.Slot(cat, true, children.firstOrNull()?.start ?: sb.length, children.lastOrNull()?.end ?: sb.length, children)
}

private fun BlockSample.call(recv: String?, name: String, args: List<String>): UiBlockNode = build("method_call", "call") {
    buildList {
        if (recv != null) { add(single("EXPRESSION", leaf("name_ref", "name", "name", recv))); add(chrome(".")) }
        add(single("NAME", leaf("name_ref", "name", "name", name)))
        add(chrome("("))
        args.forEachIndexed { i, a -> if (i > 0) add(chrome(", ")); add(single("ARGUMENT", leaf("name_ref", "name", "name", a))) }
        add(chrome(")"))
    }
}

private fun BlockSample.declStmt(): UiBlockNode = build("local_var", "var") {
    val type = single("TYPE", leaf("type_ref", "type", "type", "List<Note>"))
    val sp = chrome(" ")
    val frag = build("local_var", "var") {
        val nm = single("NAME", leaf("name_ref", "name", "name", "result"))
        val eq = chrome(" = ")
        val init = single("EXPRESSION", leaf("ClassInstanceCreation", "value", "code", "new ArrayList<>()"))
        listOf(nm, eq, init)
    }
    listOf(type, sp, single("EXPRESSION", frag), chrome(";"))
}

private fun BlockSample.ifStmt(): UiBlockNode = build("IfStatement", "if") {
    val pre = chrome("if (")
    val cond = single("EXPRESSION", call("note", "isPinned", emptyList()))
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val stmt = build("ExpressionStatement", "") { listOf(single("EXPRESSION", call("result", "add", listOf("note"))), chrome(";")) }
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(stmt)), close)
    }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

private fun BlockSample.forEachStmt(): UiBlockNode = build("EnhancedForStatement", "for") {
    val pre = chrome("for (")
    val param = build("parameter", "param") {
        val t = single("TYPE", leaf("type_ref", "type", "type", "Note"))
        val sp = chrome(" ")
        val nm = single("NAME", leaf("name_ref", "name", "name", "note"))
        listOf(t, sp, nm)
    }
    val colon = chrome(" : ")
    val iter = single("EXPRESSION", leaf("name_ref", "name", "name", "notes"))
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val ifS = ifStmt()
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(ifS)), close)
    }
    listOf(pre, single("PARAMETER", param), colon, iter, cl, single("STATEMENT", block))
}

private fun BlockSample.returnStmt(): UiBlockNode = build("ReturnStatement", "return") {
    listOf(chrome("return "), single("EXPRESSION", leaf("name_ref", "name", "name", "result")), chrome(";"))
}

/** A whole compilation unit: package + imports + a class with a field and a control-flow-rich method. */
internal fun sampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.data;")
        x.chrome("\n")
        val imp1 = x.leaf("import_decl", "import", "code", "import java.util.ArrayList;")
        x.chrome("\n")
        val imp2 = x.leaf("import_decl", "import", "code", "import java.util.List;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "NoteRepository"))
            x.chrome(" {\n    ")
            val field = x.build("field_decl", "field") { listOf(x.chrome("private int total = 0;")) }
            x.chrome("\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("public List<Note> pinned(List<Note> notes) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val decl = x.declStmt(); x.gap()
                    val forE = x.forEachStmt(); x.gap()
                    val ret = x.returnStmt()
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(decl, forE, ret)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(field, method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, imp1, imp2, cls)))
    }
    return file to x.sb.toString()
}

// ---- the typed/collapsed-call showcase (mirrors what the new JavaBlockMapping emits: qualifier +
// name/name1 fields + per-arg ARGUMENT slots, valueKinds on slots and nodes) ----

/** `<type> <name> = <literal>;` — the initializer slot (and literal) typed by the declared type. */
private fun BlockSample.typedDecl(type: String, name: String, kind: String, literal: String): UiBlockNode = build("local_var", "var") {
    val t = single("TYPE", leaf("type_ref", "type", "type", type, valueKind = "type"))
    val sp = chrome(" ")
    val frag = build("local_var", "var") {
        val nm = single("NAME", leaf("name_ref", "name", "name", name))
        val eq = chrome(" = ")
        val init = single("EXPRESSION", leaf("literal", "value", "code", literal, valueKind = kind), valueKind = kind)
        listOf(nm, eq, init)
    }
    listOf(t, sp, single("EXPRESSION", frag), chrome(";"))
}

/** `System.out.println("hi");` collapsed to ONE call block: dimmed qualifier + bold name + arg slot. */
private fun BlockSample.printlnStmt(): UiBlockNode = build("ExpressionStatement", "") {
    val call = build("method_call", "call") {
        listOf(
            field("qualifier", "System.out"), chrome("."), field("name", "println"), chrome("("),
            single("ARGUMENT", leaf("literal", "value", "code", "\"hi\"", valueKind = "string"), valueKind = "string"),
            chrome(")"),
        )
    }
    listOf(single("EXPRESSION", call), chrome(";"))
}

/** `sb.append(x).append(y).append(z);` flattened to ONE block — with ≥3 links it lays out vertically
 *  (one `.append(..)` per row) via [ChainStack] instead of wrapping inline. */
private fun BlockSample.chainStmt(): UiBlockNode = build("ExpressionStatement", "") {
    val call = build("method_call", "call") {
        listOf(
            field("qualifier", "sb"), chrome("."), field("name", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "x")),
            chrome(")."), field("name1", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "y")),
            chrome(")."), field("name2", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "z")),
            chrome(")"),
        )
    }
    listOf(single("EXPRESSION", call), chrome(";"))
}

/** An if whose boolean condition socket holds a boolean-producing infix — the green hexagon. */
private fun BlockSample.typedIfStmt(): UiBlockNode = build("IfStatement", "if") {
    val pre = chrome("if (")
    val cmp = build("InfixExpression", "", valueKind = "boolean") {
        listOf(
            single("EXPRESSION", leaf("name_ref", "name", "name", "n")),
            chrome(" < "),
            single("EXPRESSION", leaf("literal", "value", "code", "10", valueKind = "number"), valueKind = "number"),
        )
    }
    val cond = single("EXPRESSION", cmp, valueKind = "boolean")
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val stmt = printlnStmt()
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(stmt)), close)
    }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

/** A while with an EMPTY boolean condition — the hexagon hole hinting "boolean". */
private fun BlockSample.whileEmptyStmt(): UiBlockNode = build("WhileStatement", "while") {
    val pre = chrome("while (")
    val cond = empty("EXPRESSION", valueKind = "boolean")
    val cl = chrome(") ")
    val block = build("block", "block") { listOf(chrome("{"), list("STATEMENT", emptyList()), chrome("}")) }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

/** Typed sockets + collapsed calls in one unit: decls, a println, a fluent chain, an if, an empty while. */
internal fun typedSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Typed"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("void demo(StringBuilder sb, int x, int y) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val d1 = x.typedDecl("int", "n", "number", "1"); x.gap()
                    val d2 = x.typedDecl("String", "s", "string", "\"x\""); x.gap()
                    val d3 = x.typedDecl("boolean", "ok", "boolean", "true"); x.gap()
                    val p = x.printlnStmt(); x.gap()
                    val c = x.chainStmt(); x.gap()
                    val ifS = x.typedIfStmt(); x.gap()
                    val w = x.whileEmptyStmt()
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(d1, d2, d3, p, c, ifS, w)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** A 5-deep nested call `sanitize(normalize(trim(lower(read(text)))))` for the A2 depth-cap demo: past 3
 *  reporter levels the inner call collapses to a drill-in chip. Built outer-in so the text lands in order. */
private fun BlockSample.deepCall(): UiBlockNode {
    fun nest(name: String, inner: () -> UiBlockNode) = build("method_call", "call") {
        listOf(single("NAME", leaf("name_ref", "name", "name", name)), chrome("("), single("ARGUMENT", inner()), chrome(")"))
    }
    return nest("sanitize") { nest("normalize") { nest("trim") { nest("lower") { nest("read") {
        leaf("name_ref", "name", "name", "text")
    } } } } }
}

/** A unit with one statement whose initializer is [deepCall] — the canvas shows 3 nested pills then a chip. */
internal fun deepSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Deep"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("String demo(String text) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val stmt = x.build("local_var", "var") {
                        val t = x.single("TYPE", x.leaf("type_ref", "type", "type", "String", valueKind = "type"))
                        val sp = x.chrome(" ")
                        val frag = x.build("local_var", "var") {
                            val nm = x.single("NAME", x.leaf("name_ref", "name", "name", "r"))
                            val eq = x.chrome(" = ")
                            val init = x.single("EXPRESSION", x.deepCall())
                            listOf(nm, eq, init)
                        }
                        listOf(t, sp, x.single("EXPRESSION", frag), x.chrome(";"))
                    }
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(stmt)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** The expression a user drills into from the chip: `lower(read(text))`, for the [FocusSheet] demo. */
internal fun deepFocusExpr(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val node = x.build("method_call", "call") {
        listOf(
            x.single("NAME", x.leaf("name_ref", "name", "name", "lower")), x.chrome("("),
            x.single("ARGUMENT", x.build("method_call", "call") {
                listOf(x.single("NAME", x.leaf("name_ref", "name", "name", "read")), x.chrome("("), x.single("ARGUMENT", x.leaf("name_ref", "name", "name", "text")), x.chrome(")"))
            }),
            x.chrome(")"),
        )
    }
    return node to x.sb.toString()
}

/** A `name > 0` boolean comparison, for [opSampleFile]'s operands. */
private fun BlockSample.cmp(nm: String): UiBlockNode = build("InfixExpression", "", valueKind = "boolean") {
    listOf(
        single("EXPRESSION", leaf("name_ref", "name", "name", nm)),
        chrome(" > "),
        single("EXPRESSION", leaf("literal", "value", "code", "0", valueKind = "number"), valueKind = "number"),
    )
}

/** A unit with `if (x > 0 && y > 0 && z > 0 && w > 0)` — the 4-operand `&&` chain renders as an [OpStack]. */
internal fun opSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Ops"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("void demo(int x, int y, int z, int w) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val ifS = x.build("IfStatement", "if") {
                        val preIf = x.chrome("if (")
                        val cond = x.build("InfixExpression", "", valueKind = "boolean") {
                            listOf(
                                x.single("EXPRESSION", x.cmp("x"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("y"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("z"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("w"), valueKind = "boolean"),
                            )
                        }
                        val cl = x.chrome(") ")
                        val body = x.build("block", "block") {
                            listOf(x.chrome("{"), x.list("STATEMENT", listOf(x.printlnStmt())), x.chrome("}"))
                        }
                        listOf(preIf, x.single("EXPRESSION", cond, valueKind = "boolean"), cl, x.single("STATEMENT", body))
                    }
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(ifS)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** A no-op backend so previews can build a [Ctx] (completion/search return nothing). */
internal object PreviewBackend : IdeBackend,
    dev.ide.ui.backend.FileService, dev.ide.ui.backend.EditorService, dev.ide.ui.backend.BlockService,
    dev.ide.ui.backend.PreviewService, dev.ide.ui.backend.SearchService, dev.ide.ui.backend.BuildService,
    dev.ide.ui.backend.DependencyService, dev.ide.ui.backend.ModuleService, dev.ide.ui.backend.SigningService,
    dev.ide.ui.backend.ProjectService,
    dev.ide.ui.backend.SdkService, dev.ide.ui.backend.SettingsService, dev.ide.ui.backend.ActionService,
    dev.ide.ui.backend.DiagnosticsService {
    override val files get() = this
    override val editor get() = this
    override val blocks get() = this
    override val preview get() = this
    override val search get() = this
    override val build get() = this
    override val deps get() = this
    override val modules get() = this
    override val signing get() = this
    override val projects get() = this
    override val sdk get() = this
    override val settings get() = this
    override val actions get() = this
    override val diagnostics get() = this

    override val project = ProjectInfo("preview", "/preview", 1)
    override fun fileTree(mode: TreeViewMode) = TreeNode("root", "preview", NodeKind.Workspace, null)
    override fun readFile(path: String) = ""
    override fun moduleNameForFile(path: String): String? = null
    override fun updateDocument(path: String, text: String) {}
    override fun saveFile(path: String, text: String) {}
    override suspend fun complete(path: String, text: String, offset: Int) = UiCompletionResult(emptyList(), offset, offset)
    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()
    override val indexStatus: StateFlow<IndexUiStatus> = MutableStateFlow(IndexUiStatus())
    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = emptyList()
    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()
    override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState())
    override fun runBuild() {}
    override fun stopBuild() {}
}
