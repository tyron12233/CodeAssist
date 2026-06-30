package dev.ide.core

import dev.ide.ui.backend.TreeNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ProGuard/R8 keep-rule file (`*.pro`) is a recognised file type: it gets the `proguard` tree icon (not the
 * generic file glyph) and is NOT analysed as Java, so its `-keep`/`#` syntax never shows spurious diagnostics.
 */
class ProguardFileTypeTest {

    private fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap(::flatten)

    @Test
    fun proguardFileGetsItsIconAndNoJavaDiagnostics() {
        val dir = Files.createTempDirectory("ide-proguard-filetype")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val appDir = ide.moduleRoot(ide.modules().first { it.name == "app" })!!
            // Content that is NOT valid Java — if this were analysed by JDT it would report errors.
            val rules = "# Keep the entry point\n-keep class com.example.app.MainActivity { *; }\n-dontwarn **\n"
            val proguard = appDir.resolve("proguard-rules.pro")
            Files.writeString(proguard, rules)

            // (a) the tree surfaces it with the proguard icon id, not the generic "file".
            val node = flatten(backend.files.fileTree()).first { it.name == "proguard-rules.pro" }
            assertEquals("proguard", node.iconId)

            // (b) the editor analysis produces no diagnostics (it is not routed to the Java backend).
            val diags = ide.analyzeDiagnostics(proguard, rules)
            assertTrue(diags.isEmpty(), "a .pro file must not be analysed as Java, but got: $diags")
        }
        dir.toFile().deleteRecursively()
    }
}
