package dev.ide.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AIDL diagnostics in the editor, end to end through the real engine ([IdeServices.analyzeDiagnostics]).
 *
 * The point is that a `.aidl` file is analysed at all: it has no language backend, its `aidl/` root is not a
 * source root, and both of those could silently route it to "plain text, no diagnostics". These assert the
 * whole path, from the file resolving to its module through to the parser and generator errors landing on
 * the right line.
 */
class AidlInspectionTest {

    private val root = createTempDirectory("aidl-inspection")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** The classic AIDL mistake: a parcelable parameter with no direction. */
    @Test
    fun flagsAParameterMissingItsDirection() {
        val s = boot()
        writeAidl(s, "Point.aidl", "package com.example.app;\nparcelable Point;\n")
        val text = "package com.example.app;\n\nimport com.example.app.Point;\n\ninterface IMover {\n    void move(Point p);\n}\n"
        val file = writeAidl(s, "IMover.aidl", text)

        val diagnostics = runBlocking { s.analyzeDiagnostics(file, text) }
        val problem = diagnostics.singleOrNull()
        assertTrue(problem != null, "expected one AIDL error; got ${diagnostics.map { it.message }}")
        assertTrue("must say 'in', 'out' or 'inout'" in problem.message, problem.message)
        // The squiggle lands on the offending parameter, not the whole file.
        assertTrue(problem.range.start >= text.indexOf("void move"), "range ${problem.range} should be on the method line")
        assertTrue(problem.range.end <= text.indexOf("p);") + 3, "range ${problem.range} should not run past the parameter")
    }

    @Test
    fun flagsASyntaxErrorOnItsOwnLine() {
        val s = boot()
        val text = "package com.example.app;\n\ninterface IBroken {\n    int add(int a\n}\n"
        val file = writeAidl(s, "IBroken.aidl", text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("expected" in problem.message, problem.message)
        // Line 5 is the `}` the parser reached while still inside the argument list.
        val lineOfError = text.take(problem.range.start).count { it == '\n' } + 1
        assertEquals(5, lineOfError, "the error should point at the token that broke it: ${problem.message}")
    }

    /** A type declared in a sibling `.aidl` has to resolve, or every real project would be full of false errors. */
    @Test
    fun resolvesATypeDeclaredInASiblingFile() {
        val s = boot()
        writeAidl(s, "Point.aidl", "package com.example.app;\nparcelable Point;\n")
        val text = "package com.example.app;\n\nimport com.example.app.Point;\n\ninterface IMover {\n    void move(in Point p);\n}\n"
        val file = writeAidl(s, "IMover.aidl", text)

        assertEquals(emptyList(), runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message })
    }

    /** A framework type the SDK declares is classified by name rather than reported as unknown. */
    @Test
    fun acceptsAFrameworkParcelable() {
        val s = boot()
        val text = "package com.example.app;\n\nimport android.os.Bundle;\n\ninterface IConfig {\n    void apply(in Bundle values);\n}\n"
        val file = writeAidl(s, "IConfig.aidl", text)

        assertEquals(emptyList(), runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message })
    }

    @Test
    fun acceptsAValidInterface() {
        val s = boot()
        val text = """
            package com.example.app;

            /** Greets people. */
            interface IGreeter {
                const int VERSION = 1;
                String greet(String name);
                oneway void ping();
                void fill(out int[] buffer);
            }
        """.trimIndent()
        val file = writeAidl(s, "IGreeter.aidl", text)

        assertEquals(emptyList(), runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message })
    }

    private fun boot(): IdeServices = IdeServices.bootstrapDemo(root).also { services = it }

    /** Write [text] into the sample app module's `aidl/` root, under the package its files declare. */
    private fun writeAidl(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/aidl/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        // The model already declares the root; touching the tree keeps the services' view of disk current.
        services.modules()
        return file
    }
}
