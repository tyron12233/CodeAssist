package dev.codeassist.csharp.editor.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsMaskedErrorsTest {

    @Test
    fun `declared types are found`() {
        val source = """
            namespace App;
            public class Greeter { }
            internal struct Point { }
            interface IThing { }
            enum Mode { A }
            record Person(string Name);
            sealed class Box<T> { }
        """.trimIndent()
        assertEquals(setOf("Greeter", "Point", "IThing", "Mode", "Person", "Box"), declaredTypeNames(source))
    }

    @Test
    fun `a source with no types declares none`() {
        assertEquals(emptySet<String>(), declaredTypeNames("int x = 1;"))
    }

    @Test
    fun `a word that merely contains class is not a declaration`() {
        assertEquals(emptySet<String>(), declaredTypeNames("var subclass = 1; var myclass = 2;"))
    }

    @Test
    fun `a quoted type name is mentioned`() {
        val names = setOf("Greeter")
        assertTrue(mentionsType("The type or namespace name 'Greeter' could not be found", names))
        assertTrue(mentionsType("Cannot implicitly convert type 'Greeter' to 'int'", names))
    }

    @Test
    fun `a namespace prefix and generic arguments are ignored`() {
        assertTrue(mentionsType("'App.Greeter' does not contain a definition for 'Hi'", setOf("Greeter")))
        assertTrue(mentionsType("Cannot convert 'Box<int>' to 'string'", setOf("Box")))
    }

    @Test
    fun `other names are not mentioned`() {
        assertFalse(mentionsType("The name 'undefinedName' does not exist in the current context", setOf("Greeter")))
        assertFalse(mentionsType("Cannot implicitly convert type 'string' to 'int'", setOf("Greeter")))
    }

    @Test
    fun `an unquoted name is not a mention`() {
        assertFalse(mentionsType("Greeter is not accessible", setOf("Greeter")))
    }

    @Test
    fun `no left-out types mention nothing`() {
        assertFalse(mentionsType("The name 'Greeter' does not exist", emptySet()))
    }
}
