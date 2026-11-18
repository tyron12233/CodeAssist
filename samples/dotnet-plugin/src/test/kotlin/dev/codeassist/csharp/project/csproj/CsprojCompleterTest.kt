package dev.codeassist.csharp.project.csproj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsprojCompleterTest {

    private fun labels(text: String) = CsprojCompleter.suggest(text, text.length).map { it.label }

    @Test
    fun groupsUnderProject() {
        assertTrue("PropertyGroup" in labels("<Project>\n  <Pro".dropLast(3) + "<"))
        assertEquals(listOf("ItemGroup"), labels("<Project><It"))
    }

    @Test
    fun propertiesUnderPropertyGroupFilterByPrefix() {
        val found = labels("<Project><PropertyGroup><Nul")
        assertEquals(listOf("Nullable"), found)
    }

    @Test
    fun propertyInsertionClosesItself() {
        val s = CsprojCompleter.suggest("<Project><PropertyGroup><Output", 31).single()
        assertEquals("OutputType></OutputType>", s.insert)
        assertEquals("OutputType>".length, s.caret)
    }

    @Test
    fun itemInsertionPutsTheCaretInInclude() {
        val s = CsprojCompleter.suggest("<Project><ItemGroup><PackageR", 29).single()
        assertEquals("PackageReference Include=\"\" />", s.insert)
        assertEquals("PackageReference Include=\"".length, s.caret)
    }

    @Test
    fun valuesOfAnEnum() {
        assertEquals(listOf("Exe"), labels("<Project><PropertyGroup><OutputType>E"))
        assertEquals(listOf("enable", "disable").sorted(), labels("<Project><PropertyGroup><Nullable>").filter {
            it == "enable" || it == "disable"
        }.sorted())
    }

    @Test
    fun attributesLeaveOutTheWrittenOnes() {
        val found = labels("<Project><ItemGroup><PackageReference Include=\"A\" ")
        assertEquals(listOf("Version"), found)
    }

    @Test
    fun closingNameIsTheOpenElement() {
        assertEquals(listOf("PropertyGroup"), labels("<Project><PropertyGroup></"))
    }

    @Test
    fun nothingInsideAnUnknownProperty() {
        assertEquals(emptyList<String>(), labels("<Project><PropertyGroup><Unknown>"))
    }
}



