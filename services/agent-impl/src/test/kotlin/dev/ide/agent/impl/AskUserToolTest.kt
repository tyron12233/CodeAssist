package dev.ide.agent.impl

import dev.ide.agent.ToolArgs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskUserToolTest {

    private fun args(json: String) = object : ToolArgs {
        override fun string(key: String) = error("unused")
        override fun optString(key: String): String? = null
        override fun int(key: String) = error("unused")
        override fun optInt(key: String): Int? = null
        override fun boolean(key: String) = error("unused")
        override fun optBoolean(key: String): Boolean? = null
        override fun stringList(key: String) = emptyList<String>()
        override fun raw() = json
    }

    private val twoQuestions = """{"questions":[
        {"question":"Which theme?","header":"Theme","multi_select":false,"options":[
            {"label":"Material You (Recommended)","description":"Wallpaper colours on Android 12+."},
            {"label":"Fixed palette","description":"The same colours everywhere."}]},
        {"question":"Which sign-in buttons?","header":"Sign-in","multi_select":true,"options":[
            {"label":"Google","description":""},{"label":"GitHub","description":""},{"label":"Email","description":""}]}]}"""

    @Test
    fun asksAndReportsEachAnswer() {
        runBlocking {
            var asked: List<AgentQuestion>? = null
            val tool = askUserTool { qs -> asked = qs; listOf(listOf("Fixed palette"), listOf("Google", "a passkey")) }
            val result = tool.execute(args(twoQuestions))
            val qs = asked!!
            assertEquals(2, qs.size)
            assertTrue(qs[1].multiSelect)
            assertEquals("Material You (Recommended)", qs[0].options[0].label)
            assertTrue(!result.isError)
            assertTrue(result.content.contains("Which theme? -> Fixed palette"))
            assertTrue(result.content.contains("Which sign-in buttons? -> Google, a passkey"))
        }
    }

    @Test
    fun dismissedTellsTheModelToCarryOn() {
        runBlocking {
            val result = askUserTool { null }.execute(args(twoQuestions))
            assertTrue(!result.isError)
            assertTrue(result.content.contains("dismissed"))
        }
    }

    @Test
    fun malformedQuestionsAreRefusedWithoutAsking() {
        runBlocking {
            var asked = false
            val tool = askUserTool { asked = true; null }
            val oneOption = """{"questions":[{"question":"Sure?","header":"x","multi_select":false,"options":[{"label":"Yes","description":""}]}]}"""
            assertTrue(tool.execute(args(oneOption)).isError)
            assertTrue(tool.execute(args("""{"questions":[]}""")).isError)
            assertEquals(false, asked)
            assertNull(null)
        }
    }
}
