package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClangPreambleTest {

    private fun preamble(text: String): String = text.substring(0, ClangPreamble.end(text))

    @Test
    fun endsAtTheFirstLineOfCode() {
        val text = "#include <vector>\n#include \"a.h\"\n\nint main() { return 0; }\n"
        assertEquals("#include <vector>\n#include \"a.h\"\n", preamble(text))
    }

    @Test
    fun takesCommentsAndOtherDirectivesAlong() {
        val text = "// licence\n/* block\n   comment */\n#include <jni.h>\n#define TAG \"x\"\n#pragma once\nint x;\n"
        assertEquals("// licence\n/* block\n   comment */\n#include <jni.h>\n#define TAG \"x\"\n#pragma once\n", preamble(text))
    }

    @Test
    fun noIncludeMeansNoPreamble() {
        assertEquals(0, ClangPreamble.end("#define A 1\n#define B 2\nint x;\n"))
        assertEquals(0, ClangPreamble.end("int x;\n#include <vector>\n"))
        assertEquals(0, ClangPreamble.end(""))
    }

    @Test
    fun aClosedConditionalIsPartOfIt() {
        val text = "#include <a.h>\n#ifdef DEBUG\n#include <b.h>\n#endif\nint x;\n"
        assertEquals("#include <a.h>\n#ifdef DEBUG\n#include <b.h>\n#endif\n", preamble(text))
    }

    @Test
    fun neverCutsInsideAnOpenConditional() {
        // An include guard stays open to the end of the file, so the cut falls before it.
        val guarded = "#include <a.h>\n#ifndef FOO_H\n#define FOO_H\n#include <b.h>\nint x;\n#endif\n"
        assertEquals("#include <a.h>\n", preamble(guarded))
        assertEquals(0, ClangPreamble.end("#ifndef FOO_H\n#define FOO_H\n#include <b.h>\nint x;\n#endif\n"))
    }

    @Test
    fun aDirectiveWithNoNewlineAtTheEndOfTheFileIsNotACut() {
        assertEquals("#include <a.h>\n", preamble("#include <a.h>\n#include <b.h>"))
    }

    @Test
    fun continuationLinesAndCommentsInsideADirective() {
        val text = "#include <a.h>\n#define LONG(x) \\\n    ((x) + 1)\n#define URL \"http://x\" /* not\n the end */ \nint y;\n"
        assertEquals("#include <a.h>\n#define LONG(x) \\\n    ((x) + 1)\n#define URL \"http://x\" /* not\n the end */ \n", preamble(text))
    }

    @Test
    fun aTrailingLineCommentEndsTheDirective() {
        assertEquals("#include <a.h> // why\n", preamble("#include <a.h> // why\nint y;\n"))
    }

    @Test
    fun spacesBetweenHashAndName() {
        assertEquals("#  include <a.h>\n", preamble("#  include <a.h>\nint y;\n"))
    }

    @Test
    fun windowsLineEndings() {
        assertEquals("#include <a.h>\r\n", preamble("#include <a.h>\r\nint y;\r\n"))
    }

    @Test
    fun anUnterminatedCommentStopsTheScan() {
        assertEquals("#include <a.h>\n", preamble("#include <a.h>\n/* never closed\n#include <b.h>\n"))
    }

    @Test
    fun countsUtf8Bytes() {
        assertEquals(3, ClangPreamble.utf8Length("abc", 3))
        assertEquals(2, ClangPreamble.utf8Length("é", 1))
        assertEquals(3, ClangPreamble.utf8Length("€", 1))
        assertEquals(4, ClangPreamble.utf8Length("😀", 2))
        assertEquals(1 + 3, ClangPreamble.utf8Length("a€bc", 2))
    }
}
