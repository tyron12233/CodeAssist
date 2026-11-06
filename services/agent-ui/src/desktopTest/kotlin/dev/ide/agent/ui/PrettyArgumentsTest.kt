package dev.ide.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PrettyArgumentsTest {
    @Test
    fun readsAsKeyValueLines() {
        assertEquals("path: app/Login.kt\nstart_line: 10", prettyArguments("""{"path":"app/Login.kt","start_line":10}"""))
    }

    @Test
    fun multiLineValuesKeepTheirLines() {
        assertEquals("path: A.kt\ncontent:\nval a = 1\nval b = 2", prettyArguments("""{"path":"A.kt","content":"val a = 1\nval b = 2"}"""))
    }

    @Test
    fun noArgumentsShowNothing() {
        assertEquals("", prettyArguments("{}"))
        assertEquals("", prettyArguments(""))
    }
}
