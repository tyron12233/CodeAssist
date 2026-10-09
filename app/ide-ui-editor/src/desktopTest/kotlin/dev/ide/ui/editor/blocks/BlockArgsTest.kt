package dev.ide.ui.editor.blocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading signature help into parameters, and which of them a call is missing. */
class BlockArgsTest {

    @Test
    fun parameterLabelsParse() {
        assertEquals(ParamSig("text", "String", optional = false, vararg = false), parseParamLabel("text: String", kotlin = true))
        assertEquals(ParamSig("fontSize", "TextUnit", optional = true, vararg = false), parseParamLabel("fontSize: TextUnit = …", kotlin = true))
        assertEquals(ParamSig("items", "Int", optional = false, vararg = true), parseParamLabel("vararg items: Int", kotlin = true))
        assertEquals(ParamSig("count", "int", optional = false, vararg = false), parseParamLabel("int count", kotlin = false))
    }

    private val text = CallSig(listOf(listOf(
        ParamSig("text", "String", false, false), ParamSig("modifier", "Modifier", true, false),
        ParamSig("color", "Color", true, false), ParamSig("onClick", "() -> Unit", false, false),
    )), 0)

    @Test
    fun kotlinShowsMissingRequiredAndFoldsOptional() {
        val none = Supplied(emptyList(), positional = 0, named = emptySet(), trailingLambda = false, emptySlot = null)
        val (missing, hidden) = missingParams(text, none, kotlin = true, expanded = false)
        assertEquals(listOf("text", "onClick"), missing.map { it.param.name })
        assertEquals(2, hidden)
        assertTrue(!missing[0].named && missing[1].named, "the next parameter fills in line, a later one by name")
        // A trailing lambda covers the function-typed parameter; expanding shows the optional ones too.
        val lambda = Supplied(emptyList(), 0, emptySet(), trailingLambda = true, emptySlot = null)
        assertEquals(listOf("text", "modifier", "color"), missingParams(text, lambda, kotlin = true, expanded = true).first.map { it.param.name })
    }

    @Test
    fun javaOffersOnlyTheNextPosition() {
        val sig = CallSig(listOf(listOf(ParamSig("a", "int", false, false), ParamSig("b", "String", false, false))), 0)
        val (missing, hidden) = missingParams(sig, Supplied(emptyList(), 0, emptySet(), false, null), kotlin = false, expanded = false)
        assertEquals(listOf("a"), missing.map { it.param.name })
        assertEquals(0, hidden)
    }
}
