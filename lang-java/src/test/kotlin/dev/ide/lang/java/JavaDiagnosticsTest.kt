package dev.ide.lang.java

import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Step-B verification: resolution-derived semantic diagnostics (unresolved references), the gap that made a
 *  lang-java editor flip a diagnostics regression. Surfaced via `JavaParsedFile.diagnostics` — the channel the
 *  `CompilerDiagnosticProvider` fallback reads for a non-JDT backend. */
class JavaDiagnosticsTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            "package com.foo;\npublic class Greeter { public String greet(String who) { return who; } }"
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun diagnose(src: String): JavaParsedFile {
        val f = File(srcRoot, "com/foo/Use.java")
        f.writeText(src)
        return JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L)
    }

    @Test
    fun unresolvedSymbolIsReported() {
        val d = diagnose("package com.foo;\nclass Use { void m() { int x = undefinedVar; } }").diagnostics
        assertTrue(
            d.any { it.severity == Severity.ERROR && it.code == JavaDiagnosticCodes.UNRESOLVED && it.message.contains("undefinedVar") },
            "an undefined variable should be flagged; got ${d.map { it.message }}",
        )
    }

    @Test
    fun unresolvedTypeIsReportedOnce() {
        // `Systen` (typo) is the root cause; `out`/`println` are cascades and must NOT be reported separately.
        val d = diagnose("package com.foo;\nclass Use { void m() { Systen.out.println(\"x\"); } }").diagnostics
            .filter { it.code == JavaDiagnosticCodes.UNRESOLVED }
        assertTrue(d.size == 1 && d.first().message.contains("Systen"), "exactly one root unresolved; got ${d.map { it.message }}")
    }

    @Test
    fun validCodeHasNoFalsePositives() {
        // Cross-file (Greeter), java.lang (String/System), generics, a local — all resolvable.
        val d = diagnose(
            """
            package com.foo;
            import java.util.ArrayList;
            class Use {
                void m() {
                    String s = new Greeter().greet("hi");
                    System.out.println(s);
                    ArrayList<String> xs = new ArrayList<>();
                    int n = xs.size();
                }
            }
            """.trimIndent()
        ).diagnostics.filter { it.code == JavaDiagnosticCodes.UNRESOLVED }
        assertTrue(d.isEmpty(), "valid code must not produce unresolved diagnostics; got ${d.map { it.message }}")
    }

    // --- assignment-conversion (type mismatch) checks -----------------------------------------------------

    private fun typeErrors(src: String) = diagnose(src).diagnostics.filter {
        it.code == JavaDiagnosticCodes.TYPE_MISMATCH || it.code == JavaDiagnosticCodes.RETURN_VALUE
    }

    @Test
    fun incompatibleInitializerIsReported() {
        val d = typeErrors("package com.foo;\nclass Use { void m() { String s = 5; } }")
        assertTrue(
            d.any { it.code == JavaDiagnosticCodes.TYPE_MISMATCH && it.message.contains("int") && it.message.contains("String") },
            "String s = 5 should be a type mismatch; got ${d.map { it.message }}",
        )
    }

    @Test
    fun incompatibleAssignmentAndReturnAreReported() {
        val assign = typeErrors("package com.foo;\nclass Use { void m() { int x = 0; x = \"a\"; } }")
        assertTrue(assign.any { it.code == JavaDiagnosticCodes.TYPE_MISMATCH }, "x = \"a\" mismatch; got ${assign.map { it.message }}")

        val ret = typeErrors("package com.foo;\nclass Use { int m() { return \"a\"; } }")
        assertTrue(ret.any { it.code == JavaDiagnosticCodes.TYPE_MISMATCH }, "return \"a\" from int; got ${ret.map { it.message }}")
    }

    @Test
    fun returnArityIsReported() {
        val fromVoid = typeErrors("package com.foo;\nclass Use { void m() { return 1; } }")
        assertTrue(fromVoid.any { it.code == JavaDiagnosticCodes.RETURN_VALUE }, "value from void; got ${fromVoid.map { it.message }}")

        val missing = typeErrors("package com.foo;\nclass Use { int m() { return; } }")
        assertTrue(missing.any { it.code == JavaDiagnosticCodes.RETURN_VALUE }, "missing return value; got ${missing.map { it.message }}")
    }

    // --- control flow (missing return / unreachable) -----------------------------------------------------

    private fun codes(src: String, vararg wanted: String) =
        diagnose(src).diagnostics.filter { it.code in wanted }

    @Test
    fun missingReturnIsReported() {
        val empty = codes("package com.foo;\nclass Use { int m() { } }", JavaDiagnosticCodes.MISSING_RETURN)
        assertTrue(empty.isNotEmpty(), "int m() {} should be missing return; got ${empty.map { it.message }}")

        // `if (cond) return x;` at the end is still a missing return (javac reachability doesn't fold `if`).
        val ifOnly = codes(
            "package com.foo;\nclass Use { int m(boolean c) { if (c) return 1; } }", JavaDiagnosticCodes.MISSING_RETURN,
        )
        assertTrue(ifOnly.isNotEmpty(), "if-only return should be missing return; got ${ifOnly.map { it.message }}")
    }

    @Test
    fun completeReturnsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class Use {
                int a() { return 1; }
                int b(boolean c) { if (c) return 1; else return 2; }
                int c() { while (true) { } }          // never completes normally
                int d(boolean c) { if (c) { return 1; } throw new RuntimeException(); }
                void e() { }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.MISSING_RETURN, JavaDiagnosticCodes.UNREACHABLE,
        )
        assertTrue(ok.isEmpty(), "definitely-returning / non-void-complete methods must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun unreachableStatementIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { int m() { return 1; int x = 2; return x; } }", JavaDiagnosticCodes.UNREACHABLE,
        )
        assertTrue(d.isNotEmpty(), "code after return should be unreachable; got ${d.map { it.message }}")
    }

    // --- final reassignment -------------------------------------------------------------------------------

    @Test
    fun finalReassignmentIsReported() {
        val local = codes(
            "package com.foo;\nclass Use { void m() { final int x = 1; x = 2; } }", JavaDiagnosticCodes.FINAL_REASSIGNMENT,
        )
        assertTrue(local.isNotEmpty(), "reassigning a final local with an initializer should be flagged; got ${local.map { it.message }}")

        val param = codes(
            "package com.foo;\nclass Use { void m(final int p) { p++; } }", JavaDiagnosticCodes.FINAL_REASSIGNMENT,
        )
        assertTrue(param.isNotEmpty(), "incrementing a final parameter should be flagged; got ${param.map { it.message }}")
    }

    @Test
    fun blankFinalAndFieldAssignmentsAreNotFalsePositives() {
        // A blank final local assigned once, and a blank final field assigned in the constructor — both legal.
        val ok = codes(
            """
            package com.foo;
            class Use {
                final int f;
                Use() { f = 1; }
                void m() { final int x; x = 5; int y = x; }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.FINAL_REASSIGNMENT,
        )
        assertTrue(ok.isEmpty(), "blank-final local / final field init must not be flagged; got ${ok.map { it.message }}")
    }

    // --- declaration / override / duplicate / definite-assignment -----------------------------------------

    @Test
    fun abstractNotImplementedIsReported() {
        val d = codes(
            "package com.foo;\nclass Use implements Runnable { }", JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
        )
        assertTrue(d.isNotEmpty(), "a concrete class not implementing run() should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun abstractAndImplementedClassesAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            abstract class A implements Runnable { }              // abstract → fine
            class B implements Runnable { public void run() { } } // implemented → fine
            """.trimIndent(),
            JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
        )
        assertTrue(ok.isEmpty(), "abstract / fully-implemented classes must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun genericInterfaceParamImplementationIsNotFalsePositive() {
        // Implementing a generic interface whose method has a TYPE-PARAMETER parameter: the inherited signature
        // carries the raw `T`, the implementation the substituted `String` — signature matching must still see
        // it as implemented (the extremely common Comparable/Consumer/Function pattern).
        val ok = codes(
            """
            package com.foo;
            class Money implements Comparable<Money> {
                public int compareTo(Money o) { return 0; }
            }
            interface Sink<T> { void accept(T value); }
            class StringSink implements Sink<String> {
                public void accept(String value) {}
            }
            """.trimIndent(),
            JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
        )
        assertTrue(ok.isEmpty(), "a generic-interface method implemented with the substituted type must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun recordsAreNotFlaggedAsAbstractNotImplemented() {
        // java.lang.Record declares equals/hashCode/toString abstract; the compiler synthesizes them for every
        // record. PSI doesn't surface the synthesized impls, so the abstract-member check must skip records.
        val ok = codes(
            """
            package com.foo;
            record Point(int x, int y) {}
            interface Named { String name(); }
            record Person(String name, int age) implements Named {}
            """.trimIndent(),
            JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
        )
        assertTrue(ok.isEmpty(), "records must not be flagged for the compiler-synthesized equals/hashCode/toString; got ${ok.map { it.message }}")
    }

    @Test
    fun invalidOverridesAreReported() {
        val finalOverride = codes(
            "package com.foo;\nclass A { final void f() {} }\nclass B extends A { void f() {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(finalOverride.isNotEmpty(), "overriding a final method should be flagged; got ${finalOverride.map { it.message }}")

        val badAnno = codes(
            "package com.foo;\nclass Use { @Override void nope() {} }", JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(badAnno.isNotEmpty(), "@Override on a non-override should be flagged; got ${badAnno.map { it.message }}")

        val weaker = codes(
            "package com.foo;\nclass A { public void f() {} }\nclass B extends A { protected void f() {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(weaker.isNotEmpty(), "reducing visibility on override should be flagged; got ${weaker.map { it.message }}")
    }

    @Test
    fun validOverridesAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class A { void f() {} public void g() {} }
            class B extends A {
                @Override void f() {}                // valid override
                @Override public void g() {}          // same visibility
            }
            class C implements Runnable { @Override public void run() {} }
            """.trimIndent(),
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(ok.isEmpty(), "valid overrides must not be flagged; got ${ok.map { it.message }}")
    }

    /**
     * The `@Override`-on-a-non-override check must back off whenever the containing class's supertype hierarchy
     * can't be fully walked — otherwise it flags valid code whose classpath is incomplete (a missing transitive
     * dependency / SDK jar). This is the real-world Android report: `MainActivity extends AppCompatActivity` with
     * `@Override onCreate` (declared way up on `android.app.Activity`) and an anonymous `new View.OnClickListener()
     * { @Override onClick }` both flagged as "does not override" while the libraries were on the classpath — the
     * old guard only checked the DIRECT extends/implements refs (and ignored anonymous bases entirely).
     */
    @Test
    fun overrideCheckBacksOffWhenHierarchyIsIncomplete() {
        // Transitive: `Use` -> `Base` (resolves) -> `Missing` (unresolved). `whatever()` could be declared by
        // `Missing`, so `@Override` must NOT be flagged (the AppCompatActivity -> ... -> Activity.onCreate case).
        val transitive = codes(
            "package com.foo;\nclass Base extends Missing {}\nclass Use extends Base { @Override void whatever() {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(transitive.isEmpty(), "an @Override with an unresolved ANCESTOR must not be flagged; got ${transitive.map { it.message }}")

        // Anonymous class over an unresolved base (the `new View.OnClickListener() { @Override onClick }` case):
        // the base lives in the anonymous base reference, not extends/implements, so the guard must read it too.
        val anon = codes(
            "package com.foo;\nclass Use { Object o = new Missing() { @Override public void whatever() {} }; }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(anon.isEmpty(), "an @Override in an anonymous class over an unresolved base must not be flagged; got ${anon.map { it.message }}")

        // Directly-unresolved super — pre-existing behavior, keep it.
        val direct = codes(
            "package com.foo;\nclass Use extends Missing { @Override void whatever() {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(direct.isEmpty(), "an @Override with an unresolved DIRECT super must not be flagged; got ${direct.map { it.message }}")

        // Fully-resolvable hierarchy with a genuine non-override: STILL flagged (the guard doesn't over-suppress).
        val genuine = codes(
            "package com.foo;\nclass Base {}\nclass Use extends Base { @Override void whatever() {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(genuine.isNotEmpty(), "a genuine @Override-non-override in a resolvable hierarchy must still be flagged; got ${genuine.map { it.message }}")
    }

    @Test
    fun duplicateMembersAreReported() {
        val d = codes(
            "package com.foo;\nclass Use { void f() {} void f() {} int x; int x; }", JavaDiagnosticCodes.DUPLICATE_MEMBER,
        )
        assertTrue(d.size >= 2, "duplicate method + field should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun overloadsAreNotDuplicateFalsePositives() {
        val ok = codes(
            "package com.foo;\nclass Use { void f() {} void f(int a) {} void f(String s) {} }",
            JavaDiagnosticCodes.DUPLICATE_MEMBER,
        )
        assertTrue(ok.isEmpty(), "overloads must not be flagged as duplicates; got ${ok.map { it.message }}")
    }

    @Test
    fun illegalMemberShapesAreReported() {
        val abstractBody = codes(
            "package com.foo;\nabstract class Use { abstract void f() {} }", JavaDiagnosticCodes.ILLEGAL_MEMBER,
        )
        assertTrue(abstractBody.isNotEmpty(), "an abstract method with a body should be flagged; got ${abstractBody.map { it.message }}")

        val missingBody = codes(
            "package com.foo;\nclass Use { void f(); }", JavaDiagnosticCodes.ILLEGAL_MEMBER,
        )
        assertTrue(missingBody.isNotEmpty(), "a concrete method missing a body should be flagged; got ${missingBody.map { it.message }}")
    }

    @Test
    fun interfaceAndValidMembersAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            interface I { void f(); default void g() {} }   // interface abstract + default — fine
            abstract class A { abstract void h(); void k() {} } // abstract decl + concrete — fine
            """.trimIndent(),
            JavaDiagnosticCodes.ILLEGAL_MEMBER,
        )
        assertTrue(ok.isEmpty(), "interface / abstract-class members must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun uninitializedVariableIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { int m() { int x; return x; } }", JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(d.isNotEmpty(), "reading an uninitialized local should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun initializedVariablesAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class Use {
                int a() { int x = 1; return x; }
                int b(boolean c) { int x; if (c) x = 1; else x = 2; return x; } // assigned on all paths
                int d() { int x; x = 5; return x; }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(ok.isEmpty(), "definitely-assigned locals must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun uninitializedBlankFinalFieldIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { final int f; }", JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(d.any { it.message.contains("'f'") }, "a blank final field never assigned should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun assignedFinalFieldsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class Use {
                final int a = 1;                              // initializer
                final int b;                                  // assigned in constructor
                static final int c;                           // assigned in static initializer
                final int d;                                  // assigned via this.
                int e;                                        // not final — never our concern
                Use() { this.b = 2; d = 4; }
                static { c = 3; }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(ok.isEmpty(), "final fields assigned somewhere must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun enumConstantsAreNotFlaggedAsUninitialized() {
        // Enum constants are implicitly-final PsiFields with no `= expr` initializer; they must NOT be reported
        // as blank finals. A genuine blank-final field in the SAME enum still is.
        val d = codes(
            """
            package com.foo;
            enum Color {
                RED, GREEN, BLUE;
                final int code = 1;                           // initialized final — never flagged
                final int unset;                              // genuine blank final — flagged
            }
            """.trimIndent(),
            JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(
            d.none { it.message.contains("'RED'") || it.message.contains("'GREEN'") || it.message.contains("'BLUE'") },
            "enum constants must not be flagged as uninitialized; got ${d.map { it.message }}",
        )
        assertTrue(d.any { it.message.contains("'unset'") }, "a real blank final in an enum should still be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun enumConstantWithConstructorArgsIsNotFalsePositive() {
        val ok = codes(
            """
            package com.foo;
            enum Planet {
                EARTH(5.97), MARS(0.64);
                final double mass;                            // assigned in constructor
                Planet(double mass) { this.mass = mass; }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.NOT_INITIALIZED,
        )
        assertTrue(ok.isEmpty(), "enum with constructor-assigned final must be clean; got ${ok.map { it.message }}")
    }

    // --- abstract instantiation ---------------------------------------------------------------------------

    @Test
    fun abstractInstantiationIsReported() {
        val iface = codes(
            "package com.foo;\nclass Use { Runnable r = new Runnable(); }", JavaDiagnosticCodes.ABSTRACT_INSTANTIATION,
        )
        assertTrue(iface.isNotEmpty(), "instantiating an interface should be flagged; got ${iface.map { it.message }}")

        val abs = codes(
            "package com.foo;\nabstract class A {}\nclass Use { A a = new A(); }", JavaDiagnosticCodes.ABSTRACT_INSTANTIATION,
        )
        assertTrue(abs.isNotEmpty(), "instantiating an abstract class should be flagged; got ${abs.map { it.message }}")
    }

    @Test
    fun concreteAndAnonymousInstantiationsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.util.ArrayList;
            abstract class A {}
            class Use {
                Object list = new ArrayList<String>();               // concrete
                Runnable r = new Runnable() { public void run() {} };  // anonymous impl
                A a = new A() {};                                      // anonymous subclass of abstract
                Runnable[] arr = new Runnable[3];                      // array of an interface — legal
            }
            """.trimIndent(),
            JavaDiagnosticCodes.ABSTRACT_INSTANTIATION,
        )
        assertTrue(ok.isEmpty(), "concrete / anonymous / array instantiations must not be flagged; got ${ok.map { it.message }}")
    }

    // --- constructor argument applicability ---------------------------------------------------------------

    @Test
    fun inapplicableConstructorIsReported() {
        val d = codes(
            "package com.foo;\nclass P { P(int x) {} }\nclass Use { P p = new P(\"str\"); }", JavaDiagnosticCodes.CANNOT_APPLY,
        )
        assertTrue(d.isNotEmpty(), "new P(String) against P(int) should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun validConstructorsAreNotApplicabilityFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.util.ArrayList;
            class P { P(int x) {} P(String s) {} P(Object... o) {} }
            class Use {
                P a = new P(1);                         // overload (int)
                P b = new P("s");                       // overload (String)
                P c = new P(1, 2, 3);                    // varargs
                Object d = new ArrayList<String>();      // generic class (guarded out)
                StringBuilder e = new StringBuilder("x"); // library ctor
            }
            """.trimIndent(),
            JavaDiagnosticCodes.CANNOT_APPLY,
        )
        assertTrue(ok.isEmpty(), "valid (overload/varargs/generic/library) constructors must not be flagged; got ${ok.map { it.message }}")
    }

    // --- illegal break / continue -------------------------------------------------------------------------

    @Test
    fun breakOrContinueOutsideLoopIsReported() {
        val brk = codes(
            "package com.foo;\nclass Use { void m() { break; } }", JavaDiagnosticCodes.ILLEGAL_JUMP,
        )
        assertTrue(brk.isNotEmpty(), "a break outside any loop/switch should be flagged; got ${brk.map { it.message }}")

        val cont = codes(
            "package com.foo;\nclass Use { void m() { continue; } }", JavaDiagnosticCodes.ILLEGAL_JUMP,
        )
        assertTrue(cont.isNotEmpty(), "a continue outside any loop should be flagged; got ${cont.map { it.message }}")
    }

    @Test
    fun breakAndContinueInsideLoopOrSwitchAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class Use {
                void m() {
                    for (int i = 0; i < 3; i++) { if (i == 1) continue; if (i == 2) break; }
                    while (true) { break; }
                    switch (1) { case 1: break; default: break; }
                }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.ILLEGAL_JUMP,
        )
        assertTrue(ok.isEmpty(), "break/continue inside a loop or switch must not be flagged; got ${ok.map { it.message }}")
    }

    // --- cyclic inheritance -------------------------------------------------------------------------------

    @Test
    fun cyclicInheritanceIsReported() {
        assertTrue(
            codes("package com.foo;\nclass A extends A { }", JavaDiagnosticCodes.CYCLIC_INHERITANCE).isNotEmpty(),
            "a self-extending class should be flagged",
        )
        assertTrue(
            codes("package com.foo;\nclass A extends B { }\nclass B extends A { }", JavaDiagnosticCodes.CYCLIC_INHERITANCE).isNotEmpty(),
            "mutual inheritance should be flagged",
        )
    }

    @Test
    fun normalInheritanceIsNotCyclicFalsePositive() {
        val ok = codes(
            "package com.foo;\nclass A { }\nclass B extends A { }\nclass C extends B implements Runnable { public void run() {} }",
            JavaDiagnosticCodes.CYCLIC_INHERITANCE,
        )
        assertTrue(ok.isEmpty(), "a normal hierarchy must not be flagged cyclic; got ${ok.map { it.message }}")
    }

    // --- override return type + throws --------------------------------------------------------------------

    @Test
    fun incompatibleOverrideReturnAndThrowsAreReported() {
        val ret = codes(
            "package com.foo;\nclass A { String f() { return \"\"; } }\nclass B extends A { Object f() { return null; } }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(ret.isNotEmpty(), "a widened (non-covariant) return type should be flagged; got ${ret.map { it.message }}")

        val thr = codes(
            "package com.foo;\nimport java.io.IOException;\nclass A { void f() {} }\nclass B extends A { void f() throws IOException {} }",
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(thr.isNotEmpty(), "broadening the throws clause should be flagged; got ${thr.map { it.message }}")
    }

    @Test
    fun covariantReturnAndNarrowedThrowsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.io.IOException;
            import java.io.FileNotFoundException;
            interface Sup<T> { T get(); }
            class A { Object f() { return null; } void g() throws IOException {} }
            class B extends A {
                @Override String f() { return null; }                          // covariant return — fine
                @Override void g() throws FileNotFoundException {}               // narrower throws — fine
            }
            class Impl implements Sup<String> { public String get() { return ""; } } // generic-substituted — fine
            class C extends A { @Override void g() {} }                          // fewer throws — fine
            """.trimIndent(),
            JavaDiagnosticCodes.INVALID_OVERRIDE,
        )
        assertTrue(ok.isEmpty(), "covariant returns / narrowed throws / generic overrides must not be flagged; got ${ok.map { it.message }}")
    }

    // --- double final assignment --------------------------------------------------------------------------

    @Test
    fun blankFinalAssignedTwiceIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { void m() { final int x; x = 1; x = 2; } }", JavaDiagnosticCodes.FINAL_REASSIGNMENT,
        )
        assertTrue(d.isNotEmpty(), "a blank final assigned twice should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun blankFinalAssignedOncePerPathIsNotFalsePositive() {
        val ok = codes(
            "package com.foo;\nclass Use { void m(boolean c) { final int x; if (c) x = 1; else x = 2; int y = x; } }",
            JavaDiagnosticCodes.FINAL_REASSIGNMENT,
        )
        assertTrue(ok.isEmpty(), "a blank final assigned once on each path must not be flagged; got ${ok.map { it.message }}")
    }

    // --- argument applicability ---------------------------------------------------------------------------

    @Test
    fun inapplicableCallIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { void f(int x) {} void m() { f(\"str\"); } }", JavaDiagnosticCodes.CANNOT_APPLY,
        )
        assertTrue(d.isNotEmpty(), "calling f(int) with a String should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun wrongArgumentCountIsReported() {
        val tooFew = diagnose("package com.foo;\nclass Use { void f(int a, int b) {} void m() { f(1); } }").diagnostics
        assertTrue(
            tooFew.any { it.code == JavaDiagnosticCodes.CANNOT_APPLY },
            "f(1) against f(int,int) should be flagged; got ${tooFew.map { "${it.code}: ${it.message}" }}",
        )

        val tooMany = diagnose("package com.foo;\nclass Use { void f(int a) {} void m() { f(1, 2); } }").diagnostics
        assertTrue(
            tooMany.any { it.code == JavaDiagnosticCodes.CANNOT_APPLY },
            "f(1,2) against f(int) should be flagged; got ${tooMany.map { "${it.code}: ${it.message}" }}",
        )

        val none = diagnose("package com.foo;\nclass Use { void f(int a) {} void m() { f(); } }").diagnostics
        assertTrue(
            none.any { it.code == JavaDiagnosticCodes.CANNOT_APPLY },
            "f() against f(int) should be flagged; got ${none.map { "${it.code}: ${it.message}" }}",
        )
    }

    @Test
    fun inapplicableOverloadedCallIsReportedNotUnresolved() {
        // Overloaded methods: `resolve()` is null when no overload applies, which used to (a) skip the
        // applicability check and (b) mis-report "Cannot resolve symbol". Both must now be correct.
        val overload = diagnose(
            "package com.foo;\nclass Use { void g(int a) {} void g(String s) {} void m() { g(1, 2); } }",
        ).diagnostics
        assertTrue(overload.any { it.code == JavaDiagnosticCodes.CANNOT_APPLY }, "overloaded g(1,2) should be cannotApply; got ${overload.map { "${it.code}: ${it.message}" }}")
        assertTrue(overload.none { it.code == JavaDiagnosticCodes.UNRESOLVED }, "must NOT mis-report unresolved for an existing overloaded name; got ${overload.map { "${it.code}: ${it.message}" }}")

        // A real library overload set (Math.max has only 2-arg overloads).
        val lib = diagnose("package com.foo;\nclass Use { void m() { Math.max(1); } }").diagnostics
        assertTrue(lib.any { it.code == JavaDiagnosticCodes.CANNOT_APPLY }, "Math.max(1) should be cannotApply; got ${lib.map { "${it.code}: ${it.message}" }}")
        assertTrue(lib.none { it.code == JavaDiagnosticCodes.UNRESOLVED }, "must NOT mis-report unresolved for Math.max; got ${lib.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun validCallsAreNotApplicabilityFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.util.List;
            import java.util.ArrayList;
            class Use {
                void g(int x) {} void g(String s) {}          // overloads
                void v(int... xs) {}                           // varargs
                void b(Integer i) {}                           // boxing
                void w(long l) {}                              // widening
                void s(Object o) {}                            // subtype
                void n(String s) {}
                <T> void gm(T t) {}                            // generic method (guarded out)
                void m() {
                    g(1); g("a");
                    v(); v(1); v(1, 2);
                    b(1);
                    w(1);
                    s("x"); s(1);
                    n(null);
                    gm("x"); gm(1);
                    List<String> xs = new ArrayList<>(); xs.add("a"); xs.get(0);
                }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.CANNOT_APPLY,
        )
        assertTrue(ok.isEmpty(), "valid (overload/varargs/boxing/widening/subtype/generic) calls must not be flagged; got ${ok.map { it.message }}")
    }

    // --- operator operand types ---------------------------------------------------------------------------

    @Test
    fun badOperandTypeIsReported() {
        val d = codes(
            "package com.foo;\nclass Use { void m() { int x = \"a\" * 2; } }", JavaDiagnosticCodes.BAD_OPERAND,
        )
        assertTrue(d.isNotEmpty(), "a String operand to `*` should be flagged; got ${d.map { it.message }}")
    }

    @Test
    fun validOperatorsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            class Use {
                void m() {
                    String s = "a" + 1 + 'c';        // + is concatenation, not numeric
                    int a = 1 + 2 * 3 - 4 / 2 % 2;
                    int c = 'a' - 'b';                // char arithmetic
                    Integer boxed = 3; int r = boxed * 2;   // unboxing
                    boolean lt = 1 < 2;
                    boolean eq = ("a" == "b");        // == accepts any types
                    boolean and = (1 > 0) && (2 > 1);
                    long l = 1L << 3;                 // shift (excluded)
                }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.BAD_OPERAND,
        )
        assertTrue(ok.isEmpty(), "valid numeric/concat/char/boxed operators must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun realisticValidClassHasNoNewFalsePositives() {
        // A realistic, fully-valid multi-class file run through the WHOLE diagnostic pass: final fields assigned
        // via ctor / static-initializer / super, an abstract base with a concrete subclass, generic-ctor `new`,
        // and break/continue inside loops + a switch. None of the newer checks may fire.
        val d = codes(
            """
            package com.foo;
            import java.util.ArrayList;
            import java.util.List;

            abstract class Shape {
                protected final String name;
                Shape(String name) { this.name = name; }
                abstract double area();
            }
            class Circle extends Shape {
                private final double r;
                Circle(double r) { super("circle"); this.r = r; }
                @Override double area() { return 3.14 * r * r; }
            }
            class Registry {
                private final List<Shape> shapes;
                static final String TAG;
                private final int limit;
                static { TAG = "reg"; }
                Registry(int limit) { this.shapes = new ArrayList<>(); this.limit = limit; }
                void add(Shape s) {
                    for (int i = 0; i < 1; i++) {
                        if (s == null) continue;
                        if (shapes.size() >= limit) break;
                        shapes.add(s);
                    }
                }
                Shape first() {
                    switch (shapes.size()) { case 0: return null; default: break; }
                    return shapes.get(0);
                }
                List<Shape> all() { return new ArrayList<>(shapes); }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.NOT_INITIALIZED, JavaDiagnosticCodes.ABSTRACT_INSTANTIATION,
            JavaDiagnosticCodes.CANNOT_APPLY, JavaDiagnosticCodes.ILLEGAL_JUMP,
        )
        assertTrue(d.isEmpty(), "a realistic valid file must not trip the new checks; got ${d.map { "${it.code}: ${it.message}" }}")
    }

    // --- var inference ------------------------------------------------------------------------------------

    @Test
    fun varWithoutInferrableTypeIsReported() {
        val noInit = codes(
            "package com.foo;\nclass Use { void m() { var a; } }", JavaDiagnosticCodes.CANNOT_INFER_VAR,
        )
        assertTrue(noInit.isNotEmpty(), "`var a;` with no initializer should be flagged; got ${noInit.map { it.message }}")

        val nullInit = codes(
            "package com.foo;\nclass Use { void m() { var a = null; } }", JavaDiagnosticCodes.CANNOT_INFER_VAR,
        )
        assertTrue(nullInit.isNotEmpty(), "`var a = null;` should be flagged; got ${nullInit.map { it.message }}")
    }

    @Test
    fun validVarDeclarationsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.util.ArrayList;
            import java.util.List;
            class Use {
                void m() {
                    var n = 5;                              // int
                    var s = "x";                            // String
                    var list = new ArrayList<String>();     // generic
                    var cast = (String) null;               // cast gives a type
                    for (var i = 0; i < 3; i++) {}          // var in for-init
                    List<String> xs = new ArrayList<>();
                    for (var x : xs) { s = x; }             // enhanced-for var
                    int plain;                              // non-var, no initializer — legal declaration
                }
            }
            """.trimIndent(),
            JavaDiagnosticCodes.CANNOT_INFER_VAR,
        )
        assertTrue(ok.isEmpty(), "valid var declarations must not be flagged; got ${ok.map { it.message }}")
    }

    // --- unhandled checked exceptions ---------------------------------------------------------------------

    @Test
    fun unhandledCheckedExceptionIsReported() {
        val thrown = codes(
            "package com.foo;\nimport java.io.IOException;\nclass Use { void m() { throw new IOException(); } }",
            JavaDiagnosticCodes.UNHANDLED_EXCEPTION,
        )
        assertTrue(thrown.isNotEmpty(), "an uncaught, undeclared IOException should be flagged; got ${thrown.map { it.message }}")
    }

    @Test
    fun handledOrUncheckedExceptionsAreNotFalsePositives() {
        val ok = codes(
            """
            package com.foo;
            import java.io.IOException;
            class Use {
                void declared() throws IOException { throw new IOException(); }
                void caught() { try { throw new IOException(); } catch (IOException e) { } }
                void unchecked() { throw new IllegalStateException(); }   // RuntimeException — no throws needed
            }
            """.trimIndent(),
            JavaDiagnosticCodes.UNHANDLED_EXCEPTION,
        )
        assertTrue(ok.isEmpty(), "declared / caught / unchecked throws must not be flagged; got ${ok.map { it.message }}")
    }

    @Test
    fun assignmentConversionsAreNotFalsePositives() {
        // Boxing, constant narrowing, widening, null→ref, generics (diamond + explicit), an inferred generic
        // call, a lambda/conditional (poly), and a valid return — none of these are type mismatches.
        val d = typeErrors(
            """
            package com.foo;
            import java.util.List;
            import java.util.ArrayList;
            import java.util.Collections;
            class Use {
                int fromValid() { return 1; }
                void m() {
                    Integer boxed = 5;
                    byte narrowed = 5;
                    long widened = 5;
                    String nul = null;
                    List<String> diamond = new ArrayList<>();
                    List<String> explicit = new ArrayList<String>();
                    List<String> inferred = Collections.emptyList();
                    Runnable r = () -> {};
                    int cond = true ? 1 : 2;
                    Object o = "any";
                }
            }
            """.trimIndent()
        )
        assertTrue(d.isEmpty(), "valid conversions must not be flagged; got ${d.map { it.message }}")
    }
}
