package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inheritance / sealed / abstract over project-source types: super-constructor calls (inherited properties
 * land in the same instance), inherited members, override / virtual dispatch, abstract classes, and interface
 * default methods. `is`-checks against supertypes are covered in [SourceClassTest]/[Phase2FeaturesTest].
 */
class SourceInheritanceTest {

    @Test fun inheritedMethodAndProperty() {
        val code = """
            open class Animal(val name: String) {
                fun describe(): String { return name }
            }
            class Dog(name: String) : Animal(name)
            fun main(): String { return Dog("Rex").describe() }
        """.trimIndent()
        assertEquals("Rex", runProgram(code, "main/0", emptyList()))
    }

    @Test fun overrideWins() {
        val code = """
            open class Shape { open fun area(): Int { return 0 } }
            class Square(val side: Int) : Shape() { override fun area(): Int { return side * side } }
            fun main(): Int { return Square(4).area() }
        """.trimIndent()
        assertEquals(16, runProgram(code, "main/0", emptyList()))
    }

    @Test fun virtualDispatchThroughBaseReference() {
        val code = """
            open class Shape { open fun area(): Int { return 0 } }
            class Square(val side: Int) : Shape() { override fun area(): Int { return side * side } }
            fun areaOf(s: Shape): Int { return s.area() }
            fun main(): Int { return areaOf(Square(5)) }
        """.trimIndent()
        assertEquals(25, runProgram(code, "main/0", emptyList()))
    }

    @Test fun superConstructorRunsInheritedInitializers() {
        val code = """
            open class Base(val x: Int) {
                val doubled: Int = x * 2
            }
            class Derived(x: Int, val y: Int) : Base(x)
            fun main(): Int {
                val d = Derived(3, 5)
                return d.x + d.doubled + d.y
            }
        """.trimIndent()
        assertEquals(14, runProgram(code, "main/0", emptyList()))
    }

    @Test fun interfaceDefaultMethod() {
        val code = """
            interface Greeter { fun greet(): String { return "hi" } }
            class English : Greeter
            fun main(): String { return English().greet() }
        """.trimIndent()
        assertEquals("hi", runProgram(code, "main/0", emptyList()))
    }

    @Test fun abstractClassWithTemplateMethod() {
        val code = """
            abstract class Vehicle(val wheels: Int) {
                abstract fun sound(): String
                fun honk(): String { return sound() + wheels }
            }
            class Car : Vehicle(4) {
                override fun sound(): String { return "vroom" }
            }
            fun main(): String { return Car().honk() }
        """.trimIndent()
        assertEquals("vroom4", runProgram(code, "main/0", emptyList()))
    }

    @Test fun sealedHierarchyWithIsChecks() {
        val code = """
            sealed class Expr
            class Num(val value: Int) : Expr()
            class Add(val left: Int, val right: Int) : Expr()
            fun eval(e: Expr): Int = when (e) {
                is Num -> e.value
                is Add -> e.left + e.right
                else -> -1
            }
            fun main(): Int { return eval(Num(7)) + eval(Add(2, 3)) }
        """.trimIndent()
        assertEquals(12, runProgram(code, "main/0", emptyList()))
    }

    @Test fun multiLevelInheritance() {
        val code = """
            open class A(val a: Int) { fun base(): Int { return a } }
            open class B(a: Int, val b: Int) : A(a)
            class C(a: Int, b: Int, val c: Int) : B(a, b)
            fun main(): Int {
                val obj = C(1, 2, 3)
                return obj.base() + obj.b + obj.c
            }
        """.trimIndent()
        assertEquals(6, runProgram(code, "main/0", emptyList()))
    }
}
