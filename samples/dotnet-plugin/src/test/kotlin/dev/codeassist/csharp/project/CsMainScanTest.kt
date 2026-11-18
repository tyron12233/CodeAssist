package dev.codeassist.csharp.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsMainScanTest {

    private fun names(text: String) = CsMainScan.hits(text).map { it.className }

    @Test
    fun `a plain Program`() {
        val text = """
            using System;
            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine("hi");
                }
            }
        """.trimIndent()
        val hits = CsMainScan.hits(text)
        assertEquals(listOf("Program"), hits.map { it.className })
        assertEquals("Main", text.substring(hits[0].offset, hits[0].offset + 4))
        assertEquals(3, CsMainScan.lineOf(text, hits[0].offset))
    }

    @Test
    fun `block and file scoped namespaces`() {
        assertEquals(listOf("App.Tools.Cli"), names("namespace App.Tools { class Cli { static void Main() {} } }"))
        assertEquals(listOf("App.Cli"), names("namespace App;\nclass Cli { static int Main(string[] a) { return 0; } }"))
    }

    @Test
    fun `nested and several classes`() {
        val text = """
            namespace N {
                class A { static void Main() { } }
                class B { class Inner { static void Main() { } } }
            }
        """.trimIndent()
        assertEquals(listOf("N.A", "N.B.Inner"), names(text))
    }

    @Test
    fun `async and private shapes`() {
        assertEquals(listOf("P"), names("class P { static async Task Main() { await Task.Delay(1); } }"))
        assertEquals(listOf("P"), names("class P { private static async Task<int> Main(string[] args) => 0; }"))
        assertEquals(listOf("P"), names("class P { [STAThread] public static void Main() {} }"))
        assertEquals(listOf("P"), names("class P { static System.Threading.Tasks.Task Main() => null; }"))
    }

    @Test
    fun `shapes that are not entry points`() {
        assertTrue(names("class P { void Main() {} }").isEmpty())
        assertTrue(names("class P { static void Main(int x) {} }").isEmpty())
        assertTrue(names("class P { static void Main<T>() {} }").isEmpty())
        assertTrue(names("class P { static string Main() => \"\"; }").isEmpty())
        assertTrue(names("class P { static ValueTask Main() => default; }").isEmpty())
        assertTrue(names("class P<T> { static void Main() {} }").isEmpty())
        assertTrue(names("static void Main() {}").isEmpty())
    }

    @Test
    fun `a call to Main is not a declaration`() {
        assertTrue(names("class P { static void Run() { Main(); } }").isEmpty())
        assertEquals(listOf("P"), names("class P { static void Main() { Main(); } }"))
    }

    @Test
    fun `comments and strings are not code`() {
        val text = "class P {\n" +
            "  // static void Main() {}\n" +
            "  /* static void Main() { */\n" +
            "  string s = \"static void Main() {\";\n" +
            "  string v = @\"static void Main() { \"\" }\";\n" +
            "  string i = \$\"{1} static void Main() {\";\n" +
            "  char c = '{';\n" +
            "  char q = '\\'';\n" +
            "  static void Main() {}\n" +
            "}\n"
        assertEquals(listOf("P"), names(text))
    }

    @Test
    fun `a raw string and a directive`() {
        val quotes = "\"\"\""
        val text = "#region static void Main() {\nclass P {\n  string r = $quotes\n  static void Main() {\n  $quotes;\n" +
            "  static void Main() {}\n}\n#endregion\n"
        assertEquals(listOf("P"), names(text))
    }

    @Test
    fun `braces in other members do not hide the entry point`() {
        val text = """
            class P {
                int X { get; set; }
                int[] a = new int[] { 1, 2 };
                void F() { if (true) { } }
                static void Main() { }
                static void G() { }
            }
            class Q { static void Main() { } }
        """.trimIndent()
        assertEquals(listOf("P", "Q"), names(text))
    }

    @Test
    fun `records structs and constraints`() {
        assertEquals(listOf("R"), names("record R(int A) { static void Main() {} }"))
        assertEquals(listOf("S"), names("struct S { static void Main() {} }"))
        assertEquals(listOf("P"), names("class G<T> where T : class { }\nclass P { static void Main() {} }"))
    }

    @Test
    fun `mask keeps offsets`() {
        val text = "a // b\n\"c\" 'd' /* e */ f"
        val masked = CsMainScan.mask(text)
        assertEquals(text.length, masked.length)
        assertEquals('\n', masked[6])
        assertEquals('f', masked.last())
        assertEquals("a", masked.substring(0, 1))
        assertTrue(masked.substring(1, 6).isBlank())
    }

    private fun top(text: String): Int? = CsMainScan.topLevel(text)

    @Test
    fun `top-level statements are found where they start`() {
        val text = "using System;\n\nConsole.WriteLine(\"hi\");\n"
        assertEquals(text.indexOf("Console"), top(text))
        assertEquals(0, top("var x = 1;"))
        assertEquals(0, top("if (true) { }"))
        assertEquals(0, top("int[] a = { 1, 2 };"))
        assertEquals(0, top("void Local() { }\nLocal();"))
        assertEquals(0, top("await Task.Delay(1);"))
        assertEquals(0, top("using var f = Open();"))
        assertEquals(0, top("new Foo().Run();"))
    }

    @Test
    fun `declarations are not top-level statements`() {
        val text = """
            #nullable enable
            global using System;
            using static System.Math;
            using A = System.Text;
            extern alias X;
            [assembly: Foo]
            namespace N { class C { static void Main() { var x = 1; } } }
            enum Color { Red, Green }
            delegate void D();
            public record R(int A);
            public static partial class S { }
            interface I { void F(); }
            struct T { }
        """.trimIndent()
        assertNull(top(text))
        assertNull(top("namespace N;\nclass C { static void Main() { Foo(); } }"))
        assertNull(top("// Console.WriteLine();\nclass C { string s = \"a; b {\"; }"))
    }

    @Test
    fun `statements after type declarations still count`() {
        assertEquals(12, top("class C { }\nFoo();"))
    }
}
