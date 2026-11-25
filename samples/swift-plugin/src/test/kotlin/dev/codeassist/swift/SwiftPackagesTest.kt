package dev.codeassist.swift

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class SwiftPackagesTest {

    @Test
    fun theRunCopyKeepsEveryLineWhereItWas() {
        val original = "import Foundation\n\nprint(\"Name? \", terminator: \"\")\nlet n = readLine()\n"
        val run = SwiftPackages.runMain(original)
        assertEquals(original.lines().size, run.lines().size)
        assertEquals("${SwiftPackages.PROLOGUE}; import Foundation", run.lines()[0])
        assertEquals(original.lines().drop(1), run.lines().drop(1))
    }

    @Test
    fun aShebangGivesItsLineToThePrologue() {
        val run = SwiftPackages.runMain("#!/usr/bin/env swift\nprint(1)\n")
        assertEquals(listOf(SwiftPackages.PROLOGUE, "print(1)", ""), run.lines())
    }

    @Test
    fun aDirectiveOnTheFirstLineKeepsItsOwnLine() {
        val run = SwiftPackages.runMain("#if os(Android)\nprint(1)\n#endif\n")
        assertEquals(listOf(SwiftPackages.PROLOGUE, "#if os(Android)", "print(1)", "#endif", ""), run.lines())
    }

    @Test
    fun anEmptyProgramStillGetsThePrologue() {
        assertTrue(SwiftPackages.runMain("").startsWith(SwiftPackages.PROLOGUE))
    }

    @Test
    fun moduleNamesAreIdentifiers() {
        assertEquals("my_tool", SwiftPackages.moduleName("my-tool"))
        assertEquals("_2048", SwiftPackages.moduleName("2048"))
        assertEquals("App", SwiftPackages.moduleName("App"))
    }

    @Test
    fun executablesAreTheTargetsWithAMain(@TempDir pkg: Path) {
        for (dir in listOf("Tool", "Lib", "Other")) Files.createDirectories(pkg.resolve("Sources/$dir"))
        Files.write(pkg.resolve("Sources/Tool/main.swift"), "print(1)\n".toByteArray())
        Files.write(pkg.resolve("Sources/Tool/Helper.swift"), "func f() {}\n".toByteArray())
        Files.write(pkg.resolve("Sources/Lib/Lib.swift"), "public func g() {}\n".toByteArray())
        Files.write(pkg.resolve("Sources/Other/main.swift"), "print(2)\n".toByteArray())
        assertEquals(listOf("Other", "Tool"), SwiftPackages.executables(pkg))
        assertEquals(listOf("Helper.swift", "main.swift"), SwiftPackages.sources(pkg, "Tool").map { it.fileName.toString() })
    }
}
