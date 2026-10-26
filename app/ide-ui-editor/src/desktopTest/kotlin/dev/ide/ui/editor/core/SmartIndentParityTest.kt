package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where Enter and a typed `.` leave the caret, case by case against Android Studio's defaults: Kotlin continues
 * by one indent unit, Java by its continuation indent of two units (8 in a 4-space file).
 *
 * Each case is the text before (`‸` marks the caret), the keys typed, and the text after. They're checked as one
 * table so a run reports every case that's off, not just the first.
 */
class SmartIndentParityTest {

    private class Case(val name: String, val language: CodeLanguage, val before: String, val keys: String, val after: String)

    private fun kotlin(name: String, before: String, keys: String, after: String) = Case(name, CodeLanguage.Kotlin, before, keys, after)
    private fun java(name: String, before: String, keys: String, after: String) = Case(name, CodeLanguage.Java, before, keys, after)

    /** Java snippets go inside a method body unless they say otherwise. */
    private fun inMethod(body: String) = "class A {\n    void f() {\n$body\n    }\n}"

    private fun run(case: Case): String {
        val caret = case.before.indexOf('‸')
        val s = EditorSession(case.before.removeRange(caret, caret + 1), case.language, TextRange(caret))
        case.keys.forEach { s.commitText(it.toString()) }
        return s.doc.text.substring(0, s.selection.start) + "‸" + s.doc.text.substring(s.selection.start)
    }

    private fun check(cases: List<Case>) {
        val wrong = cases.mapNotNull { c ->
            val got = run(c)
            if (got == c.after) null else "${c.name}\n--- expected\n${c.after}\n--- got\n$got"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${cases.size} cases are off:\n\n" + wrong.joinToString("\n\n"))
    }

    @Test
    fun kotlinEnter() = check(
        listOf(
            kotlin("a chain link keeps the chain's indent",
                "fun f() {\n    val x = list\n        .filter { it > 0 }‸\n}", "\n",
                "fun f() {\n    val x = list\n        .filter { it > 0 }\n        ‸\n}"),
            kotlin("the `}` closing a link's lambda keeps the chain's indent",
                "fun f() {\n    list\n        .filter {\n            it\n        }‸\n}", "\n",
                "fun f() {\n    list\n        .filter {\n            it\n        }\n        ‸\n}"),
            kotlin("a statement that may still chain stays put",
                "fun f() {\n    val x = list‸\n}", "\n",
                "fun f() {\n    val x = list\n    ‸\n}"),
            kotlin("a lambda inside a chain link",
                "fun f() {\n    list\n        .map {‸}\n}", "\n",
                "fun f() {\n    list\n        .map {\n            ‸\n        }\n}"),
            kotlin("a trailing elvis continues",
                "fun f() {\n    val x = a ?:‸\n}", "\n",
                "fun f() {\n    val x = a ?:\n        ‸\n}"),
            kotlin("a trailing supertype colon continues",
                "class A :‸", "\n",
                "class A :\n    ‸"),
            kotlin("a when branch's arrow opens its body",
                "fun f() {\n    when (x) {\n        1 ->‸\n    }\n}", "\n",
                "fun f() {\n    when (x) {\n        1 ->\n            ‸\n    }\n}"),
            kotlin("after a brace-less if's body, back under the if",
                "fun f() {\n    if (x)\n        a()‸\n}", "\n",
                "fun f() {\n    if (x)\n        a()\n    ‸\n}"),
            kotlin("parameters stay siblings",
                "fun f(\n    a: Int,‸\n)", "\n",
                "fun f(\n    a: Int,\n    ‸\n)"),
        ),
    )

    @Test
    fun kotlinTypedDot() = check(
        listOf(
            kotlin("a `.` under a statement starts its chain",
                "fun f() {\n    val x = list\n    ‸\n}", ".",
                "fun f() {\n    val x = list\n        .‸\n}"),
            kotlin("a `.` under a block's `}` chains onto the block's call",
                "fun f() {\n    list.filter {\n    }\n    ‸\n}", ".",
                "fun f() {\n    list.filter {\n    }\n        .‸\n}"),
            kotlin("a `?.` starts a chain too",
                "fun f() {\n    val x = a\n    ‸\n}", "?.",
                "fun f() {\n    val x = a\n        ?.‸\n}"),
            kotlin("a `.` under a link lines up with it",
                "fun f() {\n    val x = list\n        .filter { }\n        ‸\n}", ".",
                "fun f() {\n    val x = list\n        .filter { }\n        .‸\n}"),
            kotlin("a `.` under the `}` of a link's lambda lines up with the link",
                "fun f() {\n    list\n        .filter {\n        }\n    ‸\n}", ".",
                "fun f() {\n    list\n        .filter {\n        }\n        .‸\n}"),
            kotlin("a `.` at the top of a block is left alone",
                "fun f() {\n    ‸\n}", ".",
                "fun f() {\n    .‸\n}"),
            kotlin("a `.` mid-line is just a dot",
                "fun f() {\n    list‸\n}", ".",
                "fun f() {\n    list.‸\n}"),
        ),
    )

    @Test
    fun javaEnter() = check(
        listOf(
            java("a statement without its `;` continues",
                inMethod("        String s = list‸"), "\n",
                inMethod("        String s = list\n                ‸")),
            java("a chain link keeps the chain's indent",
                inMethod("        String s = list\n                .stream()‸"), "\n",
                inMethod("        String s = list\n                .stream()\n                ‸")),
            java("the `;` ending a wrapped statement goes back to its start",
                inMethod("        String s = list\n                .stream()\n                .collect(c);‸"), "\n",
                inMethod("        String s = list\n                .stream()\n                .collect(c);\n        ‸")),
            java("arguments wrap at the continuation indent",
                inMethod("        foo(a,‸"), "\n",
                inMethod("        foo(a,\n                ‸")),
            java("a trailing operator continues",
                inMethod("        return a +‸"), "\n",
                inMethod("        return a +\n                ‸")),
            java("a wrapped operator's `;` goes back",
                inMethod("        return a +\n                b;‸"), "\n",
                inMethod("        return a +\n                b;\n        ‸")),
            java("a finished statement stays",
                inMethod("        int x = 1;‸"), "\n",
                inMethod("        int x = 1;\n        ‸")),
            java("a brace-less if opens its body",
                inMethod("        if (x)‸"), "\n",
                inMethod("        if (x)\n            ‸")),
            java("after a brace-less if's body, back under the if",
                inMethod("        if (x)\n            a();‸"), "\n",
                inMethod("        if (x)\n            a();\n        ‸")),
            java("a case label opens its body",
                inMethod("        switch (x) {\n            case 1:‸\n        }"), "\n",
                inMethod("        switch (x) {\n            case 1:\n                ‸\n        }")),
            java("a statement under a case label stays in the case",
                inMethod("        switch (x) {\n            case 1:\n                a();‸\n        }"), "\n",
                inMethod("        switch (x) {\n            case 1:\n                a();\n                ‸\n        }")),
            java("a lambda block inside a call",
                inMethod("        list.forEach(x -> {‸})"), "\n",
                inMethod("        list.forEach(x -> {\n            ‸\n        })")),
            java("a line comment doesn't continue",
                inMethod("        // hi‸"), "\n",
                inMethod("        // hi\n        ‸")),
            java("try-with-resources waits for its block",
                inMethod("        try (Res r = open())‸"), "\n",
                inMethod("        try (Res r = open())\n        ‸")),
            java("array initializer items are siblings",
                inMethod("        int[] a = {\n                1,\n                2‸\n        };"), "\n",
                inMethod("        int[] a = {\n                1,\n                2\n                ‸\n        };")),
        ),
    )

    @Test
    fun javaClassBodyEnter() = check(
        listOf(
            java("an annotation precedes its declaration",
                "class A {\n    @Override‸\n}", "\n",
                "class A {\n    @Override\n    ‸\n}"),
            java("a method header waits for its block",
                "class A {\n    void f()‸\n}", "\n",
                "class A {\n    void f()\n    ‸\n}"),
            java("a field initializer without its `;` continues",
                "class A {\n    int x = 1‸\n}", "\n",
                "class A {\n    int x = 1\n            ‸\n}"),
            java("enum constants are siblings",
                "enum E {\n    A,\n    B‸\n}", "\n",
                "enum E {\n    A,\n    B\n    ‸\n}"),
            java("an anonymous class body holds declarations",
                inMethod("        Runnable r = new Runnable() {\n            public void run()‸\n        };"), "\n",
                inMethod("        Runnable r = new Runnable() {\n            public void run()\n            ‸\n        };")),
            java("a Javadoc's end doesn't continue",
                "class A {\n    /** Doc. */‸\n}", "\n",
                "class A {\n    /** Doc. */\n    ‸\n}"),
        ),
    )

    @Test
    fun javaTypedDot() = check(
        listOf(
            java("a `.` under a statement starts its chain at the continuation indent",
                inMethod("        list\n        ‸"), ".",
                inMethod("        list\n                .‸")),
            java("a `.` after a finished statement is left alone",
                inMethod("        a();\n        ‸"), ".",
                inMethod("        a();\n        .‸")),
        ),
    )
}
