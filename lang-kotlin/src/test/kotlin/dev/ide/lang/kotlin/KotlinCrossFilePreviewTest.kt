package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.PreviewDeclProvider
import dev.ide.lang.kotlin.interp.PreviewFileModel
import dev.ide.lang.kotlin.interp.PreviewModel
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-file Compose-preview lowering: a preview that constructs/calls declarations from OTHER project files
 * (the common "preview depends on my data classes" case). The merged program + classes the interpreter runs
 * must include those reachable cross-file declarations, or the render crashes constructing a missing type.
 */
class KotlinCrossFilePreviewTest {

    private fun serviceOver(files: Map<String, String>): Pair<KotlinSymbolService, Path> {
        val dir = tempProject(files)
        return KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())) to dir
    }

    private fun lowerCrossFile(
        service: KotlinSymbolService, dir: Path, entryFile: String, entryText: String,
    ): PreviewModel {
        val kt = KotlinParserHost.parse(entryFile, entryText)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve(entryFile)), 0)
        return KotlinPreviewLowering(service).crossFileModel(parsed)
    }

    @Test
    fun constructsADataClassDeclaredInAnotherFile() {
        // `listOf(User(name = ""))` + `forEach { it.name }` where `User` lives in a sibling file — the reported
        // failure (Compose elided). The call must lower (single-element generic `listOf`), `it.name` must resolve
        // against the cross-file type, AND `User`'s lowered class must be pulled into the preview classes.
        val entry = """
            package com.example.compose
            fun makeUsers() {
                val users = listOf(User(name = ""))
                users.forEach { it.name }
            }
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "User.kt" to "package com.example.compose\n\ndata class User(val name: String)\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        val fn = assertNotNull(model.program["makeUsers/0"], "the entry function should lower")
        assertTrue(fn.isComplete, "listOf(User(...)) must lower completely; diags=${fn.diagnostics}")
        val user = assertNotNull(
            model.classes.firstOrNull { it.simpleName == "User" },
            "the cross-file User data class must be merged into the preview classes; got ${model.classes.map { it.fqn }}",
        )
        assertEquals("com.example.compose.User", user.fqn)
        assertEquals(listOf("name"), user.componentNames)
    }

    @Test
    fun callsATopLevelFunctionDeclaredInAnotherFile() {
        // A helper top-level function defined in a sibling file must be merged into the program so the
        // interpreter can interpret its body (otherwise: "no source function").
        val entry = """
            package com.example.compose
            fun caller(): Int = helper(2)
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Helper.kt" to "package com.example.compose\n\nfun helper(n: Int): Int = n + 1\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["caller/0"]?.isComplete == true, "caller should lower")
        assertNotNull(model.program["helper/1"], "the cross-file helper function must be merged into the program")
    }

    @Test
    fun pullsADataClassAndHelperFromAnotherModule() {
        // Two modules: `core` (a dependency) declares the data class + a helper; `app` (the entry) constructs/
        // calls them. `app`'s analyzer spans `core`'s sources (a module dependency folds the dep's source roots
        // into the depender's — ModuleCompilationContext), so it RESOLVES them; the cross-module dispatcher then
        // LOCATES the declaration via `app`'s analyzer but LOWERS the file with `core`'s OWN analyzer (ownership
        // routing — a dep file resolves against its own module's classpath). This is the path IdeServices drives.
        val coreDir = tempProject(
            mapOf(
                "Account.kt" to "package com.example.core\n\ndata class Account(val id: Int)\n",
                "CoreUtil.kt" to "package com.example.core\n\nfun coreHelper(): Int = 7\n",
            ),
        )
        val appDir = tempProject(
            mapOf(
                "App.kt" to """
                    package com.example.app
                    import com.example.core.Account
                    import com.example.core.coreHelper
                    fun screen() {
                        val a = Account(id = 1)
                        val n = coreHelper()
                    }
                """.trimIndent(),
            ),
        )
        // app's analyzer spans BOTH roots (mirrors a module dependency); core's analyzer spans only its own.
        val appService = KotlinSymbolService(listOf(DiskFile(appDir), DiskFile(coreDir)), listOf(stdlibJarPath()))
        val coreService = KotlinSymbolService(listOf(DiskFile(coreDir)), listOf(stdlibJarPath()))
        val appLowering = KotlinPreviewLowering(appService)
        val coreLowering = KotlinPreviewLowering(coreService)

        // The IdeServices-style provider: find a reached declaration via app's (closure-spanning) analyzer, then
        // lower it with the module that OWNS the file — core's lowering for a file under coreDir, else app's.
        fun lowerByOwner(pf: KotlinSymbolService.PreviewSourceFile): PreviewFileModel? =
            (if (pf.file.path.startsWith(coreDir.toString())) coreLowering else appLowering).loweredFile(pf)
        val provider = object : PreviewDeclProvider {
            override fun fileDeclaringType(fqn: String) = appService.sourceFileDeclaringType(fqn)?.let(::lowerByOwner)
            override fun filesDeclaringFunction(name: String) =
                appService.sourceFilesDeclaringFunction(name).mapNotNull(::lowerByOwner)
        }

        val entryKt = KotlinParserHost.parse("App.kt", Files.readString(appDir.resolve("App.kt")))
        val seed = appLowering.loweredEntryFile(KotlinParsedFile(entryKt, DiskFile(appDir.resolve("App.kt")), 0))
        val model = appLowering.expand(seed, provider)

        val screen = assertNotNull(model.program["screen/0"], "the entry should lower")
        assertTrue(screen.isComplete, "the cross-module preview should lower completely; diags=${screen.diagnostics}")
        val account = assertNotNull(
            model.classes.firstOrNull { it.simpleName == "Account" },
            "the dependency module's data class must be merged; got ${model.classes.map { it.fqn }}",
        )
        assertEquals("com.example.core.Account", account.fqn)
        assertNotNull(model.program["coreHelper/0"], "the dependency module's top-level helper must be merged")
    }

    @Test
    fun selfContainedPreviewDoesNotPullExtraFiles() {
        // A preview that references nothing cross-file lowers exactly as before (only its own declarations).
        val entry = """
            package com.example.compose
            fun localOnly(): Int { val x = 1; return x }
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Other.kt" to "package com.example.compose\n\ndata class Unused(val v: Int)\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["localOnly/0"]?.isComplete == true)
        assertTrue(
            model.classes.none { it.simpleName == "Unused" },
            "an unreferenced cross-file class must not be pulled in; got ${model.classes.map { it.fqn }}",
        )
    }
}
