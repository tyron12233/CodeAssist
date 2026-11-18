package dev.codeassist.csharp.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CsFileNamesTest {

    @Test
    fun `a matching name is fine`() {
        assertNull(CsFileNames.mismatch("public class Person {}", "Person.cs"))
        assertNull(CsFileNames.mismatch("namespace A;\npublic static class Person {}", "Person.cs"))
    }

    @Test
    fun `a different name is reported on the type name`() {
        val text = "namespace A\n{\n    public sealed class Human\n    {\n    }\n}\n"
        val m = CsFileNames.mismatch(text, "Person.cs")!!
        assertEquals("Human", m.name)
        assertEquals("Human", text.substring(m.start, m.end))
    }

    @Test
    fun `only one public type counts`() {
        assertNull(CsFileNames.mismatch("public class A {}\npublic class B {}", "Other.cs"))
        assertNull(CsFileNames.mismatch("class Internal {}", "Other.cs"))
        assertNull(CsFileNames.mismatch("public class A { public class Nested {} }", "A.cs"))
    }

    @Test
    fun `nested public types are not top level`() {
        val m = CsFileNames.mismatch("public class Outer { public class Inner {} }", "Inner.cs")
        assertNotNull(m)
        assertEquals("Outer", m!!.name)
    }

    @Test
    fun `partial parts and generics`() {
        assertNull(CsFileNames.mismatch("public partial class Person {}", "Person.Part.cs"))
        assertNull(CsFileNames.mismatch("public class Box<T> {}", "Box.cs"))
    }

    @Test
    fun `records enums interfaces and delegates`() {
        assertEquals("P", CsFileNames.mismatch("public record P(int A);", "Q.cs")!!.name)
        assertEquals("Color", CsFileNames.mismatch("public enum Color { Red }", "Q.cs")!!.name)
        assertEquals("IFoo", CsFileNames.mismatch("public interface IFoo {}", "Q.cs")!!.name)
        assertEquals("Handler", CsFileNames.mismatch("public delegate void Handler(int x);", "Q.cs")!!.name)
    }

    @Test
    fun `a file with top-level statements and non cs files are left alone`() {
        assertNull(CsFileNames.mismatch("System.Console.WriteLine(1);\npublic class A {}", "Program.cs"))
        assertNull(CsFileNames.mismatch("public class A {}", "notes.txt"))
    }

    @Test
    fun `text in strings and comments is not a type`() {
        val text = "// public class Fake {}\npublic class Real { string s = \"public class Nope {}\"; }"
        assertEquals("Real", CsFileNames.mismatch(text, "X.cs")!!.name)
    }
}
