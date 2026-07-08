package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scope + resolution for property accessors and constructor scope:
 *  - inside a getter/setter body, `field` (the backing field) and a setter's value parameter are in scope,
 *    typed to the property's type, so they complete and their members resolve;
 *  - inside an `init { }` block or a member property's initializer, the primary-constructor parameters
 *    (including non-property ones) are in scope — completed and not flagged unresolved — while a member
 *    function body (where they are NOT in scope) still flags them;
 *  - a property definitely assigned in an `init { }` block / secondary constructor is not falsely flagged
 *    `kt.mustBeInitialized`.
 */
class KotlinAccessorInitScopeTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // --- getters / setters: `field` + setter value ---

    @Test
    fun fieldCompletesInGetterBody() {
        val ls = labels("package demo\nclass C {\n  val name: String\n    get() = fie| \n}")
        assertTrue("field" in ls, "`field` must complete inside a getter body; got $ls")
    }

    @Test
    fun fieldMembersResolveInGetter() {
        // `field` is typed to the property's type (String), so its members complete.
        val ls = labels("package demo\nclass C {\n  val name: String\n    get() = field.upper| \n}")
        assertTrue(ls.any { it.startsWith("uppercase") }, "`field` (String) members must complete; got $ls")
    }

    @Test
    fun setterValueParameterCompletesAndResolves() {
        val bare = labels("package demo\nclass C {\n  var name: String = \"\"\n    set(value) { field = valu| }\n}")
        assertTrue("value" in bare, "the setter parameter `value` must complete; got $bare")
        val members = labels("package demo\nclass C {\n  var name: String = \"\"\n    set(value) { field = value.upper| }\n}")
        assertTrue(members.any { it.startsWith("uppercase") }, "`value` (String) members must complete in a setter; got $members")
    }

    @Test
    fun customSetterParameterNameResolves() {
        val members = labels("package demo\nclass C {\n  var n: String = \"\"\n    set(v) { field = v.upper| }\n}")
        assertTrue(members.any { it.startsWith("uppercase") }, "a custom setter parameter (`v`) must be typed; got $members")
    }

    @Test
    fun fieldAndValueNotFlaggedUnresolved() {
        val diags = diagnose(
            "Accessor.kt",
            "package demo\nclass C {\n  var name: String = \"\"\n    get() = field\n    set(value) { field = value }\n}",
        )
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("field") }, "`field` must resolve in an accessor; got $diags")
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("value") }, "`value` must resolve in a setter; got $diags")
    }

    // --- init blocks / property initializers: constructor scope ---

    @Test
    fun ctorParamCompletesInInitBlock() {
        val ls = labels("package demo\nclass C(name: String) {\n  init { println(nam|) }\n}")
        assertTrue("name" in ls, "a non-property constructor parameter must complete in an init block; got $ls")
    }

    @Test
    fun ctorParamMembersResolveInInitBlock() {
        val ls = labels("package demo\nclass C(name: String) {\n  init { val n = name.upper| }\n}")
        assertTrue(ls.any { it.startsWith("uppercase") }, "the ctor param `name` (String) members must complete in init; got $ls")
    }

    @Test
    fun ctorParamCompletesInPropertyInitializer() {
        val ls = labels("package demo\nclass C(name: String) {\n  val len = nam|\n}")
        assertTrue("name" in ls, "a ctor param must complete in a member property initializer; got $ls")
    }

    @Test
    fun ctorParamNotFlaggedInInitBlock() {
        val diags = diagnose("Init.kt", "package demo\nclass C(name: String) {\n  val len: Int\n  init { len = name.length }\n}")
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("name") }, "`name` must resolve in an init block; got $diags")
    }

    @Test
    fun ctorParamFlaggedInMemberFunction() {
        // A non-property primary-constructor parameter is NOT in scope in a member function body (Kotlin error).
        // Used as a bare value (a call argument), not a qualified receiver, so the unresolved-reference check
        // applies (it deliberately backs off on a receiver, which could be a package/type).
        val diags = diagnose("Method.kt", "package demo\nclass C(name: String) {\n  fun greet() { println(name) }\n}")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("name") },
            "a non-property ctor param used in a member function must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun valPropertyParamStillResolvesEverywhere() {
        // A `val`/`var` ctor parameter is a member — visible in a method too (not restricted to ctor scope).
        val diags = diagnose("ValParam.kt", "package demo\nclass C(val name: String) {\n  fun greet() { println(name) }\n}")
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("name") }, "a `val` ctor param must resolve in a method; got $diags")
    }

    // --- deferred initialization in the constructor ---

    @Test
    fun propertyAssignedInInitBlockIsNotFlaggedMissingInitializer() {
        val diags = diagnose("Deferred.kt", "package demo\nclass C {\n  val x: Int\n  init { x = 5 }\n}")
        assertTrue(diags.none { it.code == "kt.mustBeInitialized" }, "a property assigned in init { } is initialized; got $diags")
    }

    @Test
    fun propertyAssignedInSecondaryConstructorIsNotFlagged() {
        val diags = diagnose("Deferred2.kt", "package demo\nclass C {\n  val x: Int\n  constructor() { x = 7 }\n}")
        assertTrue(diags.none { it.code == "kt.mustBeInitialized" }, "a property assigned in a secondary constructor is initialized; got $diags")
    }

    @Test
    fun propertyNeverAssignedIsStillFlaggedMissingInitializer() {
        val diags = diagnose("Missing.kt", "package demo\nclass C {\n  val x: Int\n  init { println(1) }\n}")
        assertTrue(
            diags.any { it.code == "kt.mustBeInitialized" && it.message.contains("initialized") },
            "a property with no initializer and no constructor assignment is still flagged; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
