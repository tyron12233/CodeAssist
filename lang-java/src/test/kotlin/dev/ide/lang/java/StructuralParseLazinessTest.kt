package dev.ide.lang.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.impl.source.tree.LazyParseableElement
import dev.ide.psi.IntellijPsiHost
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the defining property of [IntellijPsiHost.parseStructural]: it must NOT materialize method bodies.
 *
 * Java `CODE_BLOCK` nodes are `ILazyParseableElementType` chameleons, so a structural (index) parse should
 * leave every body unparsed — bodies are the bulk of a source file's parse cost, and an index only ever reads
 * declarations. This regressed invisibly once before: the parse went through
 * `PsiFileFactory.createFileFromText(name, language, text)`, whose `markAsCopy = true` default makes
 * `PsiFileFactoryImpl.trySetupPsiForFile` run `GeneratedMarkerVisitor` over the WHOLE tree, which expands
 * every chameleon — so the "light" path silently parsed every body, on several index threads at once.
 */
class StructuralParseLazinessTest {

    private val src = """
        package p;
        public class C {
            public int m(String a) {
                int x = 0;
                for (int i = 0; i < 10; i++) { x += i; }
                return x;
            }
            int f;
        }
    """.trimIndent()

    @Test
    fun structuralParseLeavesMethodBodiesUnparsed() {
        IntellijPsiHost.warmUp()
        val bodyParsed = IntellijPsiHost.parseStructural("C.java", JavaLanguage.INSTANCE, src) { psi ->
            val cls = (psi as PsiJavaFile).classes.single()
            // Declarations must still be fully available without the body.
            assertTrue(cls.methods.single().name == "m")
            assertTrue(cls.fields.single().name == "f")
            (cls.methods.single().body as LazyParseableElement).isParsed
        }
        assertFalse(bodyParsed, "parseStructural must leave the method-body chameleon unparsed")
    }

    @Test
    fun fullParseDoesMaterializeMethodBodies() {
        IntellijPsiHost.warmUp()
        val file = IntellijPsiHost.parse("C.java", JavaLanguage.INSTANCE, src)
        val cls = (file as PsiJavaFile).classes.single()
        assertTrue(
            (cls.methods.single().body as LazyParseableElement).isParsed,
            "the editor parse path must fully materialize bodies",
        )
    }

    /**
     * The other half of the host's contract: a tree built under the parse lock must still BE there when the
     * caller traverses it unlocked. `PsiFileImpl` keeps its `FileElement` by a hard reference only while
     * `isKeepTreeElementByHardReference()` — `!viewProvider.isEventSystemEnabled` — holds; otherwise it hangs
     * off a `SoftReference` that memory pressure can clear, and the next node access reparses the file outside
     * the lock. That unlocked `buildTree` is what SIGSEGVs on 32-bit ART, so the flag is load-bearing.
     */
    @Test
    fun parsedFilesHoldTheirTreeByAHardReference() {
        IntellijPsiHost.warmUp()
        assertFalse(
            IntellijPsiHost.parse("C.java", JavaLanguage.INSTANCE, src).viewProvider.isEventSystemEnabled,
            "parse() must create a non-event-system file so its tree is held hard",
        )
        assertFalse(
            IntellijPsiHost.parseStructural("C.java", JavaLanguage.INSTANCE, src) {
                it.viewProvider.isEventSystemEnabled
            },
            "parseStructural() must create a non-event-system file so its tree is held hard",
        )
    }
}
