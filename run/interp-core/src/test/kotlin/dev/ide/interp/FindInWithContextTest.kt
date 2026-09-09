package dev.ide.interp

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.TestJars
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JetNews `BlockingFakePostsRepository.getPost`: a `suspend` member whose body is `withContext(Dispatchers.IO) {
 * posts.allPosts.find { it.id == postId } … }`, driven from a preview through `runBlocking { … }`. On device the
 * `it.id` read failed with "no readable property `id` on java.util.Arrays$ArrayList": the lambda's `it` was the
 * LIST, not an element.
 */
class FindInWithContextTest {

    @Test
    fun findLambdaInsideWithContextInASuspendMemberBindsItToTheElement() {
        val code = """
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.runBlocking
            import kotlinx.coroutines.withContext
            data class Post(val id: String, val title: String)
            data class Feed(val highlighted: Post, val recommended: List<Post>) {
                val allPosts: List<Post> get() = listOf(highlighted) + recommended
            }
            sealed class Result<out R> {
                data class Success<out T>(val data: T) : Result<T>()
                data class Error(val exception: Exception) : Result<Nothing>()
            }
            class Repo {
                private val posts = Feed(Post("a", "A"), listOf(Post("b", "B"), Post("c", "C")))
                suspend fun getPost(postId: String?): Result<Post> {
                    return withContext(Dispatchers.IO) {
                        val post = posts.allPosts.find { it.id == postId }
                        if (post == null) {
                            Result.Error(IllegalArgumentException("Unable to find post"))
                        } else {
                            Result.Success(post)
                        }
                    }
                }
            }
            val post3 = Post("c", "C")
            fun use(): String {
                val post = runBlocking {
                    (Repo().getPost(post3.id) as Result.Success).data
                }
                return post.title
            }
        """.trimIndent()
        // kotlinx.coroutines must be on the symbol service's classpath for `runBlocking`/`withContext` to resolve.
        val dir = tempProject(code)
        val coroutines = TestJars.containing("kotlinx/coroutines/BuildersKt.class")
        val service = KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath(), coroutines))
        val kt = KotlinParserHost.parse("Prog.kt", code)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Prog.kt")), 0)
        val model = KotlinPreviewLowering(service).crossFileModel(parsed)
        val diags = model.program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertEquals(emptyList(), diags, "everything must lower cleanly")
        val result = Interpreter(model.program, classes = model.classes).call(model.program.getValue("use/0"), emptyList())
        assertEquals("C", result)
    }
}
