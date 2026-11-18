package dev.codeassist.csharp.build.tests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsTestScanTest {

    private fun targets(text: String) = CsTestScan.scan(text).tests.map { "${it.suite}#${it.name}" }

    @Test
    fun `xunit facts and theories are found with their class and namespace`() {
        val text = """
            using Xunit;
            namespace Shop.Tests
            {
                public class CartTests
                {
                    [Fact]
                    public void Adds() { }

                    [Theory]
                    [InlineData(1, 2)]
                    public void Sums(int a, int b) { }

                    public void Helper() { }
                }
            }
        """.trimIndent()

        assertEquals(listOf("Shop.Tests.CartTests#Adds", "Shop.Tests.CartTests#Sums"), targets(text))
    }

    @Test
    fun `a file-scoped namespace names the suite`() {
        val text = "namespace A.B;\npublic class T { [Fact] public void One() { } }"

        assertEquals(listOf("A.B.T#One"), targets(text))
    }

    @Test
    fun `a class outside any namespace is its own suite`() {
        assertEquals(listOf("T#One"), targets("class T { [Test] public void One() { } }"))
    }

    @Test
    fun `nunit and mstest attributes are found, each method once`() {
        val text = """
            namespace N {
              [TestFixture] public class C {
                [Test] public void A() { }
                [TestCase(1)] [TestCase(2)] public void B(int x) { }
                [TestMethod] public void D() { }
                [DataTestMethod] [DataRow(1)] public void E(int x) { }
                [Test, Category("slow")] public async Task F() { }
              }
            }
        """.trimIndent()

        assertEquals(listOf("N.C#A", "N.C#B", "N.C#D", "N.C#E", "N.C#F"), targets(text))
    }

    @Test
    fun `an attribute with arguments and a qualified name is found`() {
        val text = "class T { [Fact(Skip = \"later\")] public void A() { } [Xunit.Fact] public void B() { } [FactAttribute] void C() { } }"

        assertEquals(listOf("T#A", "T#B", "T#C"), targets(text))
    }

    @Test
    fun `a nested class is named through its outer class`() {
        val text = "namespace N { class Outer { class Inner { [Fact] void A() { } } [Fact] void B() { } } }"

        assertEquals(listOf("N.Outer.Inner#A", "N.Outer#B"), targets(text))
    }

    @Test
    fun `a generic test method keeps its plain name`() {
        assertEquals(listOf("T#Any"), targets("class T { [Theory] public void Any<TValue>(TValue v) { } }"))
    }

    @Test
    fun `an attribute in a comment or a string is not a test`() {
        val text = """
            class T {
                // [Fact] public void Commented() { }
                /* [Fact] public void Blocked() { } */
                string s = "[Fact] public void Quoted() { }";
                [Fact] public void Real() { }
            }
        """.trimIndent()

        assertEquals(listOf("T#Real"), targets(text))
    }

    @Test
    fun `other attributes are not tests`() {
        val text = "class T { [Obsolete] public void A() { } [TestCategory(\"x\")] public void B() { } [Serializable] class U { } }"

        assertTrue(targets(text).isEmpty())
    }

    @Test
    fun `a suite is reported once, at its class name`() {
        val text = "namespace N;\nclass CartTests { [Fact] void A() { } [Fact] void B() { } }"

        val suites = CsTestScan.scan(text).suites

        assertEquals(listOf("N.CartTests"), suites.map { it.fullName })
        assertEquals("CartTests", suites.single().name)
        assertEquals(text.indexOf("CartTests"), suites.single().offset)
    }

    @Test
    fun `a test's offset is where its name is`() {
        val text = "class T {\n    [Fact]\n    public void Adds() { }\n}"

        assertEquals(text.indexOf("Adds"), CsTestScan.scan(text).tests.single().offset)
    }

    @Test
    fun `a class still being typed does not lose its tests`() {
        assertEquals(listOf("T#A"), targets("class T { [Fact] public void A() { "))
    }

    @Test
    fun `blanking keeps every offset and line`() {
        val text = "a /* x\ny */ \"s\\\"t\" 'c' @\"v\"\"w\" // tail\nb"

        val blanked = CsTestScan.blank(text)

        assertEquals(text.length, blanked.length)
        assertEquals(text.count { it == '\n' }, blanked.count { it == '\n' })
        assertEquals('a', blanked.first())
        assertEquals('b', blanked.last())
        assertFalse(blanked.contains("tail"))
        assertFalse(blanked.contains('s'))
    }

    @Test
    fun `a file with no test words is not scanned`() {
        assertFalse(CsTestScan.mayHoldTests("class Program { static void Main() { } }"))
        assertTrue(CsTestScan.mayHoldTests("class T { [Fact] void A() { } }"))
    }
}
