package dev.ide.core

import dev.ide.lang.kotlin.NavKind
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Find Usages and Rename through the real engine, on the Java demo project with Kotlin files added to `:core`:
 * Kotlin symbols across Kotlin files, and a Java class used from Kotlin, which must be found and renamed on
 * both sides.
 */
class FindUsagesRenameTest {

    private fun IdeServices.coreSrc(): Path = sourceRoots(modules().first { it.name == "core" }).first()

    private fun setUp(ide: IdeServices): Triple<Path, Path, Path> {
        val src = ide.coreSrc().resolve("com/example/core")
        val circle = src.resolve("Circle.kt")
        val use = src.resolve("Use.kt")
        ide.save(circle, "package com.example.core\n\nclass Circle(val r: Double) {\n    fun area() = r * r\n}\n")
        ide.save(
            use,
            "package com.example.core\n\nfun use(): Double {\n    val c = Circle(2.0)\n    println(Greeter().greet(\"k\"))\n    return c.area()\n}\n",
        )
        // Query once the project is indexed, the way the editor meets it. A lookup made mid-index can remember
        // a Java type as unknown until the next edit (go-to-declaration shares that), which is not under test.
        val deadline = System.currentTimeMillis() + 180_000
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return Triple(circle, use, src.resolve("Greeter.java"))
    }

    private fun usages(ide: IdeServices, file: Path, needle: String): List<String> {
        val text = Files.readString(file)
        return ide.navigationTargets(file, text, text.indexOf(needle), NavKind.USAGES).map { it.label }
    }

    private fun eventually(what: String, timeoutMs: Long = 60_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting until $what")
    }

    @Test
    fun findsKotlinUsagesOfAKotlinClass() { withTempDir("usages-kt") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val (circle, _, _) = setUp(ide)
            eventually("Circle's usage in Use.kt is found") {
                usages(ide, circle, "Circle(").any { it.startsWith("Use.kt:4") && "Circle(2.0)" in it }
            }
            assertTrue(usages(ide, circle, "area").any { it.startsWith("Use.kt:6") }, "the member call c.area()")
        }
    } }

    @Test
    fun renamesAKotlinClassAndItsFile() { withTempDir("rename-kt") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val (circle, use, _) = setUp(ide)
            eventually("Circle resolves from Use.kt") { usages(ide, circle, "Circle(").isNotEmpty() }
            val text = Files.readString(circle)
            val info = assertNotNull(ide.prepareRename(circle, text, text.indexOf("Circle")))
            assertEquals("Circle" to "class", info.oldName to info.kind)

            val outcome = runBlocking { ide.rename(circle, text, text.indexOf("Circle"), "Round") }
            assertTrue(outcome.success, outcome.message)
            val renamed = circle.resolveSibling("Round.kt")
            assertEquals(renamed.toString(), outcome.newPath, "the file named after the class follows it")
            assertTrue(Files.readString(renamed).contains("class Round("))
            assertTrue(Files.readString(use).contains("val c = Round(2.0)"), Files.readString(use))
            assertFalse(Files.exists(circle))
        }
    } }

    /** A Java class overriding a Kotlin member: listed as an implementation, and renamed with the member. */
    @Test
    fun aJavaOverrideOfAKotlinMemberIsAnImplementationAndIsRenamedWithIt() { withTempDir("impl-java") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.coreSrc().resolve("com/example/core")
            val animal = src.resolve("Animal.kt")
            val dog = src.resolve("Dog.java")
            val kennel = src.resolve("Kennel.java")
            ide.save(
                animal,
                "package com.example.core\n\nopen class Animal {\n    open fun sound(): String = \"\"\n" +
                    "    open fun greet(times: Int) {}\n    open fun greet(name: String) {}\n}\n\n" +
                    "class Cat : Animal() {\n    override fun greet(name: String) {}\n}\n",
            )
            ide.save(dog, "package com.example.core;\n\npublic class Dog extends Animal {\n    @Override public String sound() { return \"woof\"; }\n}\n")
            ide.save(kennel, "package com.example.core;\n\nclass Kennel {\n    String hear(Dog d) { return d.sound(); }\n}\n")
            val deadline = System.currentTimeMillis() + 180_000
            while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)

            val text = Files.readString(animal)
            val at = text.indexOf("sound")
            eventually("Dog.sound is listed as an implementation") {
                ide.navigationTargets(animal, text, at, NavKind.IMPLEMENTATION).any { it.label == "sound  ·  Dog" && it.file.path.endsWith("Dog.java") }
            }

            // Overloads: Cat overrides greet(String) only, and Java must not be asked about a Kotlin class (its
            // light-class lookup matches by name and would offer Cat.greet(String) for greet(Int)).
            val greetInt = text.indexOf("greet(times")
            val greetString = text.indexOf("greet(name")
            assertEquals(emptyList(), ide.navigationTargets(animal, text, greetInt, NavKind.IMPLEMENTATION).map { it.label })
            assertEquals(listOf("greet  ·  Cat"), ide.navigationTargets(animal, text, greetString, NavKind.IMPLEMENTATION).map { it.label })

            val outcome = runBlocking { ide.rename(animal, text, at, "speak") }
            assertTrue(outcome.success, outcome.message)
            assertTrue(Files.readString(animal).contains("open fun speak()"))
            assertTrue(Files.readString(dog).contains("public String speak()"), Files.readString(dog))
            assertTrue(Files.readString(kennel).contains("d.speak()"), "the Java call to the override; " + Files.readString(kennel))
        }
    } }

    /** Java code using Kotlin declarations: found and renamed with them, overloads and property accessors included. */
    @Test
    fun javaCallersOfKotlinDeclarationsAreFoundAndRenamed() { withTempDir("java-callers") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.coreSrc().resolve("com/example/core")
            val pet = src.resolve("Pet.kt")
            val parrot = src.resolve("Parrot.java")
            val owner = src.resolve("Owner.java")
            ide.save(
                pet,
                "package com.example.core\n\nopen class Pet {\n    var title: String = \"\"\n" +
                    "    open fun greet(times: Int) {}\n    open fun greet(name: String) {}\n}\n",
            )
            ide.save(
                parrot,
                "package com.example.core;\n\npublic class Parrot extends Pet {\n" +
                    "    @Override public void greet(String name) {}\n    @Override public void greet(int times) {}\n}\n",
            )
            ide.save(
                owner,
                "package com.example.core;\n\nclass Owner {\n    void walk(Pet p) {\n        p.greet(3);\n        p.greet(\"hi\");\n" +
                    "        p.setTitle(p.getTitle() + \"!\");\n        Pet q = new Pet();\n    }\n}\n",
            )
            val deadline = System.currentTimeMillis() + 180_000
            while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val petText = Files.readString(pet)
            val ownerText = Files.readString(owner)
            val parrotText = Files.readString(parrot)

            // Find Usages on one overload finds the Java call that binds to it, not the other overload's.
            val greetInt = petText.indexOf("greet(times")
            eventually("Owner's greet(3) is a usage of greet(times: Int)") {
                usages(ide, pet, "greet(times").any { it.startsWith("Owner.java:5") }
            }
            assertTrue(usages(ide, pet, "greet(times").none { it.startsWith("Owner.java:6") }, "greet(\"hi\") is the other overload")

            // Go to Implementation picks the Java override with the matching parameters.
            val impl = ide.navigationTargets(pet, petText, greetInt, NavKind.IMPLEMENTATION)
            assertEquals(listOf(parrotText.indexOf("greet(int")), impl.filter { it.file.path.endsWith("Parrot.java") }.map { it.offset })

            // A property is reached from Java through its accessors.
            assertTrue(usages(ide, pet, "title").any { it.startsWith("Owner.java:7") }, "getTitle()/setTitle()")

            // Renaming the property renames the accessors Java calls.
            val titleOutcome = runBlocking { ide.rename(pet, petText, petText.indexOf("title"), "caption") }
            assertTrue(titleOutcome.success, titleOutcome.message)
            assertTrue(Files.readString(owner).contains("p.setCaption(p.getCaption() + \"!\")"), Files.readString(owner))

            // Renaming the class renames Java's uses of it.
            val petNow = Files.readString(pet)
            val classOutcome = runBlocking { ide.rename(pet, petNow, petNow.indexOf("Pet"), "Animal2") }
            assertTrue(classOutcome.success, classOutcome.message)
            assertTrue(Files.readString(parrot).contains("extends Animal2"), Files.readString(parrot))
            assertTrue(Files.readString(owner).contains("Animal2 q = new Animal2()"), Files.readString(owner))
            assertTrue(Files.readString(owner).contains("void walk(Animal2 p)"), Files.readString(owner))
            assertEquals(ownerText.length - 2 * "Pet".length + 2 * "Animal2".length + 2 * ("Caption".length - "Title".length) +
                "Animal2".length - "Pet".length, Files.readString(owner).length, "nothing else changed")
        }
    } }

    @Test
    fun aJavaClassIsFoundAndRenamedInKotlinToo() { withTempDir("rename-java") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val (_, use, greeter) = setUp(ide)
            eventually("Greeter's Kotlin usage is found from the Java declaration") {
                usages(ide, greeter, "Greeter {").any { it.startsWith("Use.kt:5") }
            }
            assertTrue(usages(ide, greeter, "Greeter {").any { it.startsWith("Formatter.java") }, "and the Java ones")

            val text = Files.readString(greeter)
            val outcome = runBlocking { ide.rename(greeter, text, text.indexOf("Greeter {"), "Welcomer") }
            assertTrue(outcome.success, outcome.message)
            assertTrue(Files.readString(use).contains("println(Welcomer().greet(\"k\"))"), Files.readString(use))
            assertTrue(Files.exists(greeter.resolveSibling("Welcomer.java")))
        }
    } }
}
