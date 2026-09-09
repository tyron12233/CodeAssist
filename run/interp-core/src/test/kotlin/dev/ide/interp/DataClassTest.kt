package dev.ide.interp

import kotlin.test.Test

class DataClassTest {

    @Test
    fun testDataClass() {
        val code = """
            data class Project(
                val id: String
            )
            
            fun main() {
                val project = Project("hello")
            }
        """.trimIndent()
        runProgram(code, "main/0", listOf())
    }
}