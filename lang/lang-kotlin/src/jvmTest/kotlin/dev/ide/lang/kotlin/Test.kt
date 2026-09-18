package dev.ide.lang.kotlin

import java.util.Locale

fun main() {
    println("Hello World")

    runCatching {
        println("HEllo World".uppercase(Locale.getDefault()))
    }

    listOf("String").map { i -> 1}

    ArrayList<String>()

    val a = listOf("")

}