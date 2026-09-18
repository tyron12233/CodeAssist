package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A FUNCTION name used as a VALUE without being invoked (`fun Test() { LazyColumn }`, a bare `helper`) must be
 * flagged — the compiler's "Function invocation 'X(...)' expected". Conservative: a real call, a `::` reference,
 * a top-level/local VALUE of the same name, a member read, or a classifier (its own check) must NOT be flagged.
 */
class FunctionUsedAsValueTest {

    private fun hasFunctionCallExpected(src: String): Boolean {
        val dir = tempProject(mapOf("Main.kt" to src))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val doc = SnippetDoc(src, DiskFile(dir.resolve("Main.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .any { it.code == "kt.functionCallExpected" }
    }

    @Test fun flagsBareFunctionAsStatement() =
        assertTrue(hasFunctionCallExpected("package demo\nfun helper() {}\nfun f() { helper }\n"))

    @Test fun flagsBareFunctionAsInitializer() =
        assertTrue(hasFunctionCallExpected("package demo\nfun helper() {}\nfun f() { val x = helper }\n"))

    @Test fun flagsBareFunctionAsArgument() =
        assertTrue(hasFunctionCallExpected("package demo\nfun helper() {}\nfun g(p: () -> Unit) {}\nfun f() { g(helper) }\n"))

    @Test fun doesNotFlagCalledFunction() =
        assertFalse(hasFunctionCallExpected("package demo\nfun helper() {}\nfun f() { helper() }\n"))

    @Test fun doesNotFlagCallableReference() =
        assertFalse(hasFunctionCallExpected("package demo\nfun helper() {}\nfun f() { val x = ::helper }\n"))

    @Test fun doesNotFlagTopLevelValueRead() =
        assertFalse(hasFunctionCallExpected("package demo\nval helper = 1\nfun f() { helper }\n"))

    @Test fun doesNotFlagLocalValueRead() =
        assertFalse(hasFunctionCallExpected("package demo\nfun f() { val helper = 1\nhelper }\n"))

    @Test fun doesNotFlagLocalLambdaRead() =
        assertFalse(hasFunctionCallExpected("package demo\nfun f() { val helper = {}\nhelper }\n"))

    // A bare enclosing-class member FUNCTION read is flagged (kind-aware); a member VALUE read is not.
    @Test fun flagsEnclosingMemberFunctionRead() =
        assertTrue(hasFunctionCallExpected("package demo\nclass C { fun m() {}\nfun f() { m } }\n"))

    @Test fun doesNotFlagEnclosingMemberValueRead() =
        assertFalse(hasFunctionCallExpected("package demo\nclass C { val m = 1\nfun f() { m } }\n"))

    // A classifier used as a value is the CLASSIFIER_AS_VALUE case, not this one.
    @Test fun doesNotFlagClassifierAsValue() =
        assertFalse(hasFunctionCallExpected("package demo\nclass Foo(val n: Int)\nfun f() { val x = Foo }\n"))
}
