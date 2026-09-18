package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Kotlin scope functions (`let`/`run`/`with`/`apply`/`also`) exercised end-to-end across the three editor
 * dimensions the user cares about, over Kotlin (user class + stdlib), Java (public field + accessor), and
 * generic receivers:
 *  - **typing** — the implicit `this` (apply/run/with) and `it` (let/also) receiver types, and the call's own
 *    return type (apply/also → the receiver, let/run/with → the lambda result) flowing through chains/nesting;
 *  - **setting variables** — assigning a property through the implicit receiver (`w.apply { text = "x" }`):
 *    it resolves (not flagged unresolved), and a wrong-typed assignment is a `kt.typeMismatch`;
 *  - **marking used / unused** — locals declared inside a scope lambda are typed and unused-flagged, and an
 *    outer local / private member referenced only inside a scope lambda counts as used.
 */
class KotlinScopeFunctionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private fun diagnostics(code: String): List<dev.ide.lang.dom.Diagnostic> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics
    }

    private fun codes(code: String): List<String?> = diagnostics(code).map { it.code }

    // ---------------------------------------------------------------------------------------------------
    // A. Typing — implicit `this` receiver members complete inside apply / run / with.
    // ---------------------------------------------------------------------------------------------------

    @Test fun applyThisMemberCompletes() {
        assertTrue("text" in labels("package demo\nfun f() { Widget().apply { te| } }"), "apply: `this` is Widget")
    }

    @Test fun runThisMemberCompletes() {
        assertTrue("width" in labels("package demo\nfun f() { Widget().run { wid| } }"), "run: `this` is Widget")
    }

    @Test fun withThisMemberCompletes() {
        assertTrue("enabled" in labels("package demo\nfun f() { with(Widget()) { ena| } }"), "with: `this` is Widget")
    }

    @Test fun explicitThisMemberCompletesInApply() {
        assertTrue("describe" in labels("package demo\nfun f() { Widget().apply { this.des| } }"), "apply: explicit this.member")
    }

    // ---------------------------------------------------------------------------------------------------
    // B. Typing — the implicit `it` parameter is the receiver type inside let / also.
    // ---------------------------------------------------------------------------------------------------

    @Test fun letItIsTyped() {
        assertTrue("text" in labels("package demo\nfun f() { Widget().let { it.te| } }"), "let: `it` is Widget")
    }

    @Test fun alsoItIsTyped() {
        assertTrue("render" in labels("package demo\nfun f() { Widget().also { it.ren| } }"), "also: `it` is Widget")
    }

    // ---------------------------------------------------------------------------------------------------
    // C. Typing — the scope function's OWN return type (apply/also → receiver, let/run/with → result).
    // ---------------------------------------------------------------------------------------------------

    @Test fun applyReturnsReceiver() {
        assertTrue("describe" in labels("package demo\nfun f() { Widget().apply { }.des| }"), "apply returns the receiver")
    }

    @Test fun alsoReturnsReceiver() {
        assertTrue("render" in labels("package demo\nfun f() { Widget().also { }.ren| }"), "also returns the receiver")
    }

    @Test fun letReturnsLambdaResult() {
        assertTrue("uppercase" in labels("package demo\nfun f() { Widget().let { it.describe() }.upper| }"), "let returns the lambda result (String)")
    }

    @Test fun runReturnsLambdaResult() {
        assertTrue("uppercase" in labels("package demo\nfun f() { Widget().run { describe() }.upper| }"), "run returns the lambda result (String)")
    }

    @Test fun withReturnsLambdaResult() {
        assertTrue("uppercase" in labels("package demo\nfun f() { with(Widget()) { describe() }.upper| }"), "with returns the lambda result (String)")
    }

    // ---------------------------------------------------------------------------------------------------
    // D. Typing — nesting and named lambda parameters.
    // ---------------------------------------------------------------------------------------------------

    @Test fun nestedScopeFunctionReceiver() {
        // Inside the inner also, `it` is what render() returned (Widget).
        assertTrue("describe" in labels("package demo\nfun f() { Widget().apply { render().also { it.des| } } }"), "nested also: it is Widget")
    }

    @Test fun namedLambdaParameterIsTyped() {
        assertTrue("text" in labels("package demo\nfun f() { Widget().let { w -> w.te| } }"), "let with a named param: w is Widget")
    }

    // ---------------------------------------------------------------------------------------------------
    // E. Setting variables — assignment through the implicit receiver RESOLVES (no false unresolved).
    // ---------------------------------------------------------------------------------------------------

    @Test fun assignmentViaApplyReceiverResolves() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f() { Widget().apply { text = \"x\"\n  width = 5\n  enabled = true } }"), "apply property assignments resolve via the implicit receiver")
    }

    @Test fun assignmentViaWithReceiverResolves() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f() { with(Widget()) { enabled = true } }"), "with property assignment resolves")
    }

    @Test fun readViaApplyReceiverResolves() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f() { Widget().apply { println(text) } }"), "a bare property read resolves via the implicit receiver")
    }

    // ---------------------------------------------------------------------------------------------------
    // F. Setting variables — the assigned value is type-checked against the property type.
    // ---------------------------------------------------------------------------------------------------

    @Test fun wrongTypedAssignmentViaReceiverIsFlagged() {
        assertTrue("kt.typeMismatch" in codes("package demo\nfun f() { Widget().apply { width = \"x\" } }"), "String into an Int property must mismatch")
        assertTrue("kt.typeMismatch" in codes("package demo\nfun f() { Widget().apply { text = 5 } }"), "Int into a String property must mismatch")
    }

    @Test fun correctlyTypedAssignmentViaReceiverIsClean() {
        assertTrue("kt.typeMismatch" !in codes("package demo\nfun f() { Widget().apply { width = 5\n  text = \"ok\"\n  enabled = true } }"), "matching assignments must not mismatch")
    }

    @Test fun explicitThisAssignmentIsTypeChecked() {
        assertTrue("kt.typeMismatch" in codes("package demo\nfun f() { Widget().apply { this.width = \"x\" } }"), "this.width = String must mismatch")
    }

    // ---------------------------------------------------------------------------------------------------
    // G. Local declarations inside scope-function lambdas.
    // ---------------------------------------------------------------------------------------------------

    @Test fun unusedLocalInsideLambdaIsFlagged() {
        assertTrue("kt.unusedLocal" in codes("package demo\nfun f() { Widget().apply { val unusedInner = 1 } }"), "a local never used inside the lambda warns")
    }

    @Test fun usedLocalInsideLambdaIsClean() {
        assertTrue("kt.unusedLocal" !in codes("package demo\nfun f() { Widget().let { val n = it.describe()\n  println(n) } }"), "a local used inside the lambda must not warn")
    }

    @Test fun lambdaLocalIsTyped() {
        assertTrue("uppercase" in labels("package demo\nfun f() { Widget().let { val n = it.describe()\n  n.upper| } }"), "a local typed from `it` inside the lambda completes")
    }

    // ---------------------------------------------------------------------------------------------------
    // H. Marking used / unused across the lambda boundary.
    // ---------------------------------------------------------------------------------------------------

    @Test fun outerLocalUsedOnlyInsideLambdaCountsAsUsed() {
        assertTrue("kt.unusedLocal" !in codes("package demo\nfun f() { val outer = 5\n  Widget().apply { width = outer } }"), "an outer local read only inside the lambda is used")
    }

    @Test fun outerVarReassignedOnlyInsideLambdaIsNotValHint() {
        assertTrue("kt.varCouldBeVal" !in codes("package demo\nfun f() { var acc = 0\n  Widget().apply { acc = width }\n  println(acc) }"), "a var reassigned inside the lambda is not val-able")
    }

    @Test fun privateMemberUsedOnlyInsideLambdaCountsAsUsed() {
        assertTrue("kt.unusedPrivate" !in codes("package demo\nclass Host {\n  private fun secret() = 1\n  fun f() { Widget().apply { width = secret() } }\n}"), "a private member called only inside the lambda is used")
    }

    // ---------------------------------------------------------------------------------------------------
    // I. Java receiver type — public field assignment + method calls through the implicit receiver.
    // ---------------------------------------------------------------------------------------------------

    @Test fun javaPublicFieldCompletesInApply() {
        assertTrue("caption" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().apply { capt| } }"), "Java public field via apply receiver")
    }

    @Test fun javaMethodCompletesInApply() {
        assertTrue("greet" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().apply { gre| } }"), "Java method via apply receiver")
    }

    @Test fun javaFieldAssignmentViaReceiverResolves() {
        assertTrue("kt.unresolved" !in codes("import com.example.JavaPanel\nfun f() { JavaPanel().apply { caption = \"x\" } }"), "Java public-field assignment resolves")
    }

    @Test fun javaFieldAssignmentIsTypeChecked() {
        assertTrue("kt.typeMismatch" in codes("import com.example.JavaPanel\nfun f() { JavaPanel().apply { caption = 5 } }"), "Int into a String Java field must mismatch")
    }

    @Test fun javaScopeFunctionReturnsReceiver() {
        assertTrue("greet" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().apply { }.gre| }"), "apply on a Java instance returns the receiver")
        assertTrue("greet" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().also { it.gre| } }"), "also: `it` is the Java instance")
    }

    // ---------------------------------------------------------------------------------------------------
    // J. Generic receiver — the receiver's type ARGUMENT flows into the scope lambda (pinned receiver).
    // ---------------------------------------------------------------------------------------------------

    @Test fun genericReceiverMembersAreInScope() {
        // A generic receiver (`Box<String>`) flows in as the scope lambda's implicit `this`, so its members
        // complete without a qualifier.
        val items = labels("package demo\nfun f(b: Box<String>) { b.apply { unwr| } }")
        assertTrue("unwrap" in items, "generic receiver's members are the implicit `this` inside apply; got $items")
    }

    @Test fun genericMemberTypeIsSubstitutedDirectly() {
        // `Box<String>.value` (declared `T`) is String → `.uppercase` resolves, with and without a scope fn.
        assertTrue("uppercase" in labels("package demo\nfun f(b: Box<String>) { b.value.upper| }"), "value: T → String (direct)")
        assertTrue("uppercase" in labels("package demo\nfun f(b: Box<String>) { b.unwrap().upper| }"), "unwrap(): T → String (direct)")
    }

    @Test fun genericMemberTypeIsSubstitutedInsideScopeLambda() {
        // Inside `b.apply { }`, `this` is Box<String>, so the bare `value` member is String.
        assertTrue("uppercase" in labels("package demo\nfun f(b: Box<String>) { b.apply { value.upper| } }"), "value is String inside apply")
        assertTrue("uppercase" in labels("package demo\nfun f(b: Box<String>) { b.let { it.value.upper| } }"), "it.value is String inside let")
    }

    // ---------------------------------------------------------------------------------------------------
    // K. Constructor type-argument inference — `Box("s")` is Box<String> (inferred from the ctor argument).
    // ---------------------------------------------------------------------------------------------------

    @Test fun constructorTypeArgumentIsInferredFromArguments() {
        assertTrue("uppercase" in labels("package demo\nfun f() { Box(\"s\").value.upper| }"), "Box(\"s\") → Box<String>; value is String")
        assertTrue("uppercase" in labels("package demo\nfun f() { Box(\"s\").apply { value.upper| } }"), "Box(\"s\") → Box<String> inside apply")
    }

    // ---------------------------------------------------------------------------------------------------
    // L. Java bean accessors → synthetic Kotlin properties (resolve + complete + type-check), and the
    //    use-property-access warning that nudges an explicit accessor call toward property syntax.
    // ---------------------------------------------------------------------------------------------------

    @Test fun javaGetterBecomesSyntheticProperty() {
        // getLabel()/setLabel(String) → property `label`; isOpen()/setOpen(boolean) → property `isOpen`.
        assertTrue("label" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().apply { lab| } }"), "synthetic `label` completes")
        assertTrue("isOpen" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().apply { isOp| } }"), "synthetic `isOpen` completes")
    }

    @Test fun syntheticPropertyTypeFlows() {
        // `label` is String (the getter return), so a chain off it resolves String members.
        assertTrue("uppercase" in labels("import com.example.JavaPanel\nfun f() { JavaPanel().label.upper| }"), "label is String")
    }

    @Test fun syntheticPropertyAssignmentResolvesAndTypeChecks() {
        assertTrue("kt.unresolved" !in codes("import com.example.JavaPanel\nfun f() { JavaPanel().apply { label = \"x\" } }"), "synthetic-property assignment resolves")
        assertTrue("kt.typeMismatch" in codes("import com.example.JavaPanel\nfun f() { JavaPanel().apply { label = 5 } }"), "Int into a String synthetic property must mismatch")
    }

    @Test fun explicitGetterCallWarnsUsePropertyAccess() {
        val diags = diagnostics("import com.example.JavaPanel\nfun f() { JavaPanel().getLabel() }")
        assertTrue(diags.any { it.code == "kt.usePropertyAccess" && it.message.contains("label") }, "getLabel() should suggest `label`; got $diags")
    }

    @Test fun explicitSetterCallWarnsUsePropertyAccess() {
        val diags = diagnostics("import com.example.JavaPanel\nfun f() { JavaPanel().setLabel(\"x\") }")
        assertTrue(diags.any { it.code == "kt.usePropertyAccess" && it.message.contains("label = \"x\"") }, "setLabel(\"x\") should suggest `label = \"x\"`; got $diags")
    }

    @Test fun explicitIsSetterCallSuggestsTheIsProperty() {
        // setOpen(true) pairs with the isOpen() getter → suggest `isOpen = true` (not `open = true`).
        val diags = diagnostics("import com.example.JavaPanel\nfun f() { JavaPanel().setOpen(true) }")
        assertTrue(diags.any { it.code == "kt.usePropertyAccess" && it.message.contains("isOpen = true") }, "setOpen(true) should suggest `isOpen = true`; got $diags")
    }

    @Test fun nonAccessorMethodDoesNotWarn() {
        assertTrue("kt.usePropertyAccess" !in codes("import com.example.JavaPanel\nfun f() { JavaPanel().greet() }"), "a non-accessor method must not warn")
    }

    @Test fun kotlinGetterFunctionDoesNotWarn() {
        // `getName()` is a real Kotlin function on a Kotlin source class — NOT a Java accessor, so no warning.
        assertTrue("kt.usePropertyAccess" !in codes("package demo\nfun f(w: Widget) { w.getName() }"), "a Kotlin fun getX() is not an accessor")
    }

    // ---------------------------------------------------------------------------------------------------
    // M. Same-file editing — a class declared in the SAME buffer resolves its members live (the focal-source
    //    sync), so scope-function checks/completion work before the file is saved and reindexed.
    // ---------------------------------------------------------------------------------------------------

    @Test fun sameFileClassNullAssignViaWithIsFlagged() {
        // The reported scenario: AddressBook is declared in the same file; `street = null` in a with-block flags.
        val code = "package demo\nfun f() { with(AddressBook()) { street = null } }\n" +
            "class AddressBook { var street: String = \"\" }"
        assertTrue("kt.typeMismatch" in codes(code), "same-file class: street = null via with must flag; got ${diagnostics(code)}")
    }

    @Test fun sameFileClassWrongTypeAssignViaApplyIsFlagged() {
        val code = "package demo\nfun f() { AddressBook().apply { count = \"x\" } }\n" +
            "class AddressBook { var count: Int = 0 }"
        assertTrue("kt.typeMismatch" in codes(code), "same-file class: count = \"x\" via apply must flag; got ${diagnostics(code)}")
    }

    @Test fun sameFileClassMemberCompletesViaScopeReceiver() {
        val code = "package demo\nfun f() { with(AddressBook()) { stre| } }\nclass AddressBook { var street: String = \"\" }"
        assertTrue("street" in labels(code), "same-file class member completes via with; got ${labels(code)}")
        val onInstance = "package demo\nfun f() { val a = AddressBook()\n  a.stre| }\nclass AddressBook { var street: String = \"\" }"
        assertTrue("street" in labels(onInstance), "same-file class member completes on an instance; got ${labels(onInstance)}")
    }

    @Test fun sameFileClassValidAssignViaWithIsClean() {
        val code = "package demo\nfun f() { with(AddressBook()) { street = \"x\"\n  count = 5 } }\n" +
            "class AddressBook { var street: String = \"\"\n  var count: Int = 0 }"
        assertTrue("kt.typeMismatch" !in codes(code), "matching same-file assignments must not flag; got ${diagnostics(code)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Widget.kt" to """
                    package demo
                    class Widget {
                        var text: String = ""
                        var width: Int = 0
                        var enabled: Boolean = false
                        val id: Int = 0
                        fun render(): Widget = this
                        fun describe(): String = text
                        fun getName(): String = text
                    }
                """.trimIndent(),
                "Box.kt" to """
                    package demo
                    class Box<T>(var value: T) {
                        fun unwrap(): T = value
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), javaPanelJar())))

        /** A synthetic Java class: a public mutable field, a public method, and a getLabel/setLabel accessor
         *  pair (to probe whether a Kotlin synthetic property is surfaced from Java accessors). */
        private fun javaPanelJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/JavaPanel", null, "java/lang/Object", null)
            // public String caption;  (non-final → assignable)
            cw.visitField(Opcodes.ACC_PUBLIC, "caption", "Ljava/lang/String;", null, null).visitEnd()
            // public JavaPanel() {}
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
            }
            // public String greet() { return null; }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()Ljava/lang/String;", null, null).apply {
                visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 1); visitEnd()
            }
            // public String getLabel() / public void setLabel(String) — Java-bean accessor pair → property `label`.
            cw.visitMethod(Opcodes.ACC_PUBLIC, "getLabel", "()Ljava/lang/String;", null, null).apply {
                visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 1); visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "setLabel", "(Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(1, 2); visitEnd()
            }
            // public boolean isOpen() / public void setOpen(boolean) — `is`-accessor pair → property `isOpen`.
            cw.visitMethod(Opcodes.ACC_PUBLIC, "isOpen", "()Z", null, null).apply {
                visitCode(); visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN); visitMaxs(1, 1); visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "setOpen", "(Z)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(1, 2); visitEnd()
            }
            cw.visitEnd()
            val jar = Files.createTempFile("javapanel", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/example/JavaPanel.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
