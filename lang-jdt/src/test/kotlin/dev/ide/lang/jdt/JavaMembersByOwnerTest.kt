package dev.ide.lang.jdt

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.lang.jdt.index.JavaMembersByOwnerIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The producer half of cross-language source interop: a `.java` source file is indexed by OWNER FQN, public
 * members only (so a Kotlin file can enumerate a same-project Java class's members without it being compiled).
 */
class JavaMembersByOwnerTest {

    private fun javaInput(name: String, src: String) = object : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("")
        override val unitName = name
        override val sourcePath: Path? = null
        override fun bytes() = ByteArray(0)
        override fun text() = src
        override fun dom() = null
    }

    @Test
    fun indexesPublicMembersKeyedByOwnerFqn() {
        val src = """
            package com.example;
            public class JavaSrc {
                public String greet() { return ""; }
                public int count;
                private int secret;
                String packagePrivate() { return ""; }
            }
        """.trimIndent()
        val out = JavaMembersByOwnerIndex.index(javaInput("JavaSrc.java", src))
        val members = out["com.example.JavaSrc"].orEmpty().map { it.name }
        assertTrue("greet" in members, "public method indexed under the owner FQN; got $out")
        assertTrue("count" in members, "public field indexed under the owner FQN; got $members")
        assertTrue("secret" !in members, "private member must be excluded; got $members")
        assertTrue("packagePrivate" !in members, "non-public member excluded (visibility-safe); got $members")
    }
}
