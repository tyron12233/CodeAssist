package dev.codeassist.csharp.project.csproj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsprojTextTest {

    private val sample = """
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <OutputType>Exe</OutputType>
            <!-- <Nullable>bad</Nullable> -->
          </PropertyGroup>
          <ItemGroup>
            <PackageReference Include="Foo" Version="1.0" />
          </ItemGroup>
        </Project>
    """.trimIndent()

    @Test
    fun elementsKnowTheirParentAndText() {
        val els = CsprojText.elements(sample)
        val output = els.single { it.name == "OutputType" }
        assertEquals("PropertyGroup", output.parent)
        assertEquals("Exe", output.text)
        assertEquals("Exe", sample.substring(output.textStart, output.textEnd))
    }

    @Test
    fun commentedElementsAreNotElements() {
        assertTrue(CsprojText.elements(sample).none { it.name == "Nullable" })
    }

    @Test
    fun attributeRangesCoverTheValue() {
        val ref = CsprojText.elements(sample).single { it.name == "PackageReference" }
        val include = ref.attr("Include")!!
        assertEquals("Foo", sample.substring(include.valueStart, include.valueEnd))
        assertTrue(ref.selfClosing)
        assertEquals("ItemGroup", ref.parent)
    }

    @Test
    fun positionInsideProjectChildren() {
        val text = "<Project>\n  <"
        val position = CsprojText.positionAt(text, text.length)
        assertTrue(position is CsprojText.Position.TagName)
        assertEquals("Project", (position as CsprojText.Position.TagName).parent)
    }

    @Test
    fun positionInValueAndAttribute() {
        val value = "<Project><PropertyGroup><Nullable>en"
        val v = CsprojText.positionAt(value, value.length) as CsprojText.Position.Value
        assertEquals("Nullable", v.element)
        assertEquals("PropertyGroup", v.parent)
        assertEquals("en", v.typed)

        val attr = "<Project><ItemGroup><Reference Pri"
        val a = CsprojText.positionAt(attr, attr.length) as CsprojText.Position.AttributeName
        assertEquals("Reference", a.element)
        assertEquals("Pri", a.typed)

        val quoted = "<Project><ItemGroup><Using Static=\"t"
        val q = CsprojText.positionAt(quoted, quoted.length) as CsprojText.Position.AttributeValue
        assertEquals("Static", q.attribute)
        assertEquals("t", q.typed)
    }

    @Test
    fun noPositionInsideAComment() {
        val text = "<Project><!-- <Nu"
        assertNull(CsprojText.positionAt(text, text.length))
        assertNotNull(CsprojText.positionAt("<Project><", 10))
    }
}
