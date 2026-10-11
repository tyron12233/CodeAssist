package dev.ide.android.support.apilevels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkApiLevelsTest {
    private val jdk = assertNotNull(JdkApiLevels.bundled(), "jdk-api-levels.bin is bundled")

    private fun since(owner: String, name: String, descriptor: String?, arity: Int = -1) =
        jdk.methodLevel(owner, name, descriptor, arity)?.since

    @Test
    fun knowsWhenJdkApisArrived() {
        assertTrue(jdk.platformLevel >= 25)
        assertEquals(8, since("java.util.List", "add", "(Ljava/lang/Object;)Z"))
        assertEquals(9, since("java.util.List", "of", null, 0))
        assertEquals(11, since("java.lang.String", "strip", "()Ljava/lang/String;"))
        assertEquals(11, since("java.lang.String", "isBlank", null, 0))
        assertEquals(16, since("java.util.stream.Stream", "toList", null, 0))
        assertEquals(21, since("java.util.List", "reversed", null, 0))
        assertEquals(9, jdk.classLevel("java.lang.Module")?.since)
        assertEquals(11, jdk.classLevel("java.net.http.HttpClient")?.since)
        assertEquals(8, jdk.classLevel("java.lang.String")?.since)
    }

    @Test
    fun aCovariantOverrideOfAnOlderMethodIsAsOldAsTheMethod() {
        // Java 9 added `ByteBuffer flip()` over Java 8's `Buffer flip()`; `buffer.flip()` compiles at 8.
        assertEquals(8, since("java.nio.ByteBuffer", "flip", "()Ljava/nio/ByteBuffer;"))
        // An inherited method is found through the supertype walk.
        assertEquals(8, since("java.util.ArrayList", "stream", null, 0))
    }

    @Test
    fun knowsDeprecationsAndRemovals() {
        val integerCtor = assertNotNull(jdk.methodLevel("java.lang.Integer", "<init>", "(I)V"))
        assertEquals(9, integerCtor.deprecatedIn)
        assertTrue(assertNotNull(jdk.classLevel("java.util.jar.Pack200")).removedIn in 14..15)
        assertNull(jdk.classLevel("com.example.NotAJdkClass"))
    }
}
