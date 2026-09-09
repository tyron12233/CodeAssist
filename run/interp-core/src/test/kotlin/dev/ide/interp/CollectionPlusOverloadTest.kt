package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `listOf(a) + list` must pick `Collection<T>.plus(elements: Iterable<T>)`, not `plus(element: T)`: the latter
 * nests the second list as ONE element. JetNews `PostsFeed.allPosts = listOf(highlightedPost) + recommendedPosts
 * + …` then `find { it.id == postId }` read `id` on that nested list ("no readable property `id` on
 * java.util.Arrays$ArrayList").
 */
class CollectionPlusOverloadTest {
    @Test
    fun listPlusListConcatenates() {
        assertEquals(3, runProgram("fun use(): Int = (listOf(1) + listOf(2, 3)).size", "use/0", emptyList()))
        assertEquals(4, runProgram("fun use(): Int = (listOf(1) + listOf(2, 3) + listOf(4)).size", "use/0", emptyList()))
    }

    @Test
    fun listPlusElementAppends() {
        assertEquals(2, runProgram("fun use(): Int = (listOf(1) + 2).size", "use/0", emptyList()))
    }

    @Test
    fun dataClassListPlusListThenFind() {
        val code = """
            data class Post(val id: String)
            data class Feed(val highlighted: Post, val recommended: List<Post>, val popular: List<Post>) {
                val allPosts: List<Post> get() = listOf(highlighted) + recommended + popular
            }
            fun use(): String? = Feed(Post("a"), listOf(Post("b"), Post("c")), listOf(Post("d"))).allPosts.find { it.id == "c" }?.id
        """.trimIndent()
        assertEquals("c", runProgram(code, "use/0", emptyList()))
    }
}
