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
    fun callsATopLevelExtensionDeclaredInAnotherFile() {
        // The reported "Compose Preview fails to resolve top-level extension function ... no source extension
        // `<function>/0`": a top-level extension (`fun String.shout()`) declared in a sibling file, called from
        // the entry. The resolver correctly tags it a SOURCE extension, but the expander never merged its
        // declaring file (no EXTENSION branch), so the interpreter threw "no source extension `shout/0`". It is
        // keyed in the program by `name/valueParams` (the receiver is not a value parameter), like a top-level fn.
        val entry = """
            package com.example.compose
            fun caller(): String = "hi".shout()
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Ext.kt" to "package com.example.compose\n\nfun String.shout(): String = this + \"!\"\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["caller/0"]?.isComplete == true, "caller should lower; diags=${model.program["caller/0"]?.diagnostics}")
        assertNotNull(
            model.program["shout/0"],
            "the cross-file top-level extension must be merged into the program; got ${model.program.keys}",
        )
    }

    @Test
    fun readsATopLevelPropertyDeclaredInAnotherFile() {
        // The reported "Preview failed to render: no source function `Purple80/0`": a top-level `val` (a theme
        // color) defined in a sibling file, read from the entry (`Text(color = Purple80)` in miniature). A
        // top-level property read lowers to a `Purple80/0` TOP_LEVEL call (its synthetic zero-arg getter), so the
        // declaring file must be merged into the program like a function, not skipped as "not a function".
        val entry = """
            package com.example.compose
            fun useColor(): Int = Purple80
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Theme.kt" to "package com.example.compose\n\nval Purple80 = 0xFF0000\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["useColor/0"]?.isComplete == true, "useColor should lower; diags=${model.program["useColor/0"]?.diagnostics}")
        assertNotNull(
            model.program["Purple80/0"],
            "the cross-file top-level property's synthetic getter must be merged; got ${model.program.keys}",
        )
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
    fun mergesACrossFileSuperclassOfAConstructedSubclass() {
        // Constructing `HomeCard()` (in Card.kt) whose superclass `BaseCard` lives in Base.kt: the expander must
        // pull in the SUPERCLASS's file too, or super-init never runs (inherited `title` reads null) and an
        // inherited call throws "no member". Before the fix the expander requested only the constructed type's
        // file — `reachableSourceClasses` followed supertypes but the runtime-model expander did not.
        val entry = """
            package com.example.compose
            fun make(): BaseCard = HomeCard()
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Base.kt" to "package com.example.compose\n\nabstract class BaseCard(val title: String = \"base\")\n",
                "Card.kt" to "package com.example.compose\n\nclass HomeCard : BaseCard(\"home\")\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["make/0"]?.isComplete == true, "make() should lower; diags=${model.program["make/0"]?.diagnostics}")
        assertNotNull(model.classes.firstOrNull { it.simpleName == "HomeCard" }, "the constructed subclass must be merged")
        assertNotNull(
            model.classes.firstOrNull { it.simpleName == "BaseCard" },
            "the cross-file SUPERCLASS must be merged so super-init runs; got ${model.classes.map { it.fqn }}",
        )
    }

    @Test
    fun mergesASourceEnumReachedOnlyAsAReifiedTypeArgument() {
        // `enumValues<Suit>()` where `enum Suit` lives in another file and appears ONLY as a type argument. The
        // expander must follow `Call.typeArguments`, or Suit's file never merges and the `enumValues` reified
        // intrinsic can't find its lowered enum (the preview hard-fails).
        val entry = """
            package com.example.compose
            fun names(): List<String> = enumValues<Suit>().map { it.name }
        """.trimIndent()
        val (service, dir) = serviceOver(
            mapOf(
                "Suit.kt" to "package com.example.compose\n\nenum class Suit { HEARTS, SPADES }\n",
                "Use.kt" to entry,
            ),
        )
        val model = lowerCrossFile(service, dir, "Use.kt", entry)
        assertTrue(model.program["names/0"]?.isComplete == true, "names() should lower; diags=${model.program["names/0"]?.diagnostics}")
        assertNotNull(
            model.classes.firstOrNull { it.simpleName == "Suit" },
            "the source enum reached only as a type argument must be merged; got ${model.classes.map { it.fqn }}",
        )
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
