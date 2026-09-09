package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: `Modifier.semantics { role = Role.RadioButton }` in a @Preview failed at render with
 * "no writable property `role` on …SemanticsConfiguration". `role` is a LIBRARY extension property
 * (`var SemanticsPropertyReceiver.role`) whose setter is a STATIC, MANGLED method on `SemanticsPropertiesKt`
 * (`setRole-<hash>`, its `Role` value-class parameter unboxed to `int`) — not a member setter on the receiver.
 * The interpreter's `PropertySet` path now routes an extension-property WRITE through the dispatcher (so its
 * mangling-aware resolution + value-class coercion apply), mirroring the extension-property READ.
 *
 * Driven via `SemanticsConfiguration().apply { role = … }`: `apply` gives the bare `role =` the same
 * implicit-receiver resolution as `semantics { }` (an extension property on the receiver-lambda's `this`) and
 * the interpreter runs the block eagerly — a `semantics { }` modifier invokes its lambda lazily during
 * semantics collection, which a headless composition never performs.
 */
class SemanticsRolePropertyWriteTest {


    @Test
    fun extensionPropertyWriteRoutesToMangledFacadeSetter() {
        // `apply { role = … }` gives the bare `role =` the same implicit-receiver resolution as `semantics { }`;
        // reading the result back through the erased generic `get<T>` needs a method call on the value class
        // `Role` (a separate interpreter concern), so assert via `contains` (a plain `boolean`) that the write
        // stored the property — which is exactly what threw before the fix.
        val code = """
            package demo
            import androidx.compose.ui.semantics.Role
            import androidx.compose.ui.semantics.SemanticsConfiguration
            import androidx.compose.ui.semantics.SemanticsProperties
            import androidx.compose.ui.semantics.role
            fun box(): Boolean {
                val config = SemanticsConfiguration()
                config.apply { role = Role.RadioButton }
                return config.contains(SemanticsProperties.Role)
            }
        """.trimIndent()
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val result = Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
        assertTrue(result == true, "the `role` extension-property write must reach the mangled facade setter and store the property (was: $result)")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
