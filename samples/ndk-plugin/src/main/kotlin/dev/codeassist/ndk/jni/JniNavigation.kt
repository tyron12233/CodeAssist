package dev.codeassist.ndk.jni

import dev.codeassist.ndk.NdkFacet
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DeclarationProvider
import dev.ide.analysis.NavigationTarget
import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.editor.DecorationTint
import dev.ide.plugin.editor.EditorDecorationContext
import dev.ide.plugin.editor.EditorDecorationProvider
import dev.ide.plugin.editor.EditorDecorations
import dev.ide.plugin.editor.GutterMark

/**
 * The modules the plugin has been handed, by directory, for the places the host hands it only a path: an
 * editor decoration and a gutter tap. Filled by everything that does receive a `Module` (the diagnostics,
 * the build), which run on every file the user opens.
 */
object JniModules {
    private val byDir = LinkedHashMap<String, Module>()

    @Synchronized
    fun remember(module: Module) {
        byDir[module.dir.path.trimEnd('/')] = module
    }

    /** The module owning [path]: the longest remembered directory containing it. */
    @Synchronized
    fun forPath(path: String): Module? =
        byDir.entries.filter { path.startsWith(it.key + "/") }.maxByOrNull { it.key.length }?.value
}

/** One end of a JNI pair in the file being looked at, and where its other end is. */
data class JniLink(
    val nameStart: Int,
    val nameEnd: Int,
    val targetPath: String,
    val targetOffset: Int,
    val label: String,
)

object JniLinks {

    /**
     * Every JNI pair with one end in [path] ([text] = its live buffer): a native method and the C/C++ function
     * implementing it, or a `Java_…` function and the method it implements.
     */
    fun linksIn(module: Module, path: String, text: String): List<JniLink> {
        val facet = module.facets.get(NdkFacet.KEY) ?: return emptyList()
        val snapshot = JniIndex.snapshot(module, facet, path, text)
        val fromJvm = snapshot.natives.filter { it.path == path }.mapNotNull { declared ->
            val m = declared.method
            val impl = snapshot.implementationOf(m) ?: return@mapNotNull null
            JniLink(m.nameOffset, m.nameOffset + m.name.length, impl.path, impl.function.nameOffset,
                "${impl.function.name} in ${impl.path.substringAfterLast('/')}")
        }
        val fromCpp = snapshot.functions.filter { it.path == path }.mapNotNull { defined ->
            val f = defined.function
            val decl = snapshot.declarationOf(f) ?: return@mapNotNull null
            JniLink(f.nameOffset, f.nameOffset + f.name.length, decl.path, decl.method.nameOffset,
                "${decl.method.className.substringAfterLast('.')}.${decl.method.name} in ${decl.path.substringAfterLast('/')}")
        }
        return fromJvm + fromCpp
    }

    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }
}

private val JNI_FILE_SUFFIXES = listOf(".java", ".kt", ".c", ".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hh", ".hxx")

/** A gutter mark beside each end of a JNI pair; tapping it opens the other end. */
class JniGutterMarks : EditorDecorationProvider {
    override val id = "ndk.jni.gutter"

    override fun appliesTo(ctx: EditorDecorationContext): Boolean = JNI_FILE_SUFFIXES.any { ctx.path.endsWith(it) }

    override suspend fun decorate(ctx: EditorDecorationContext): EditorDecorations {
        val module = JniModules.forPath(ctx.path) ?: return EditorDecorations.EMPTY
        val links = runCatching { JniLinks.linksIn(module, ctx.path, ctx.text) }.getOrDefault(emptyList())
        if (links.isEmpty()) return EditorDecorations.EMPTY
        val toCpp = ctx.path.endsWith(".java") || ctx.path.endsWith(".kt")
        return EditorDecorations(
            gutter = links.map { link ->
                GutterMark(
                    line = JniLinks.lineOf(ctx.text, link.nameStart),
                    iconId = "code",
                    tint = DecorationTint.Accent,
                    tooltip = (if (toCpp) "Go to the C/C++ implementation: " else "Go to the native method: ") + link.label,
                    actionId = NAVIGATE_ACTION_ID,
                )
            },
        )
    }

    companion object {
        const val NAVIGATE_ACTION_ID = "dev.codeassist.ndk.jni.navigate"
    }
}

/**
 * Opens the other end of the JNI pair on the line a gutter mark was tapped on (or the caret's line, from the
 * editor's action menu).
 */
fun jniNavigateAction(): SimpleAction = SimpleAction(
    id = JniGutterMarks.NAVIGATE_ACTION_ID,
    text = "Go to JNI counterpart",
    places = setOf(ActionPlaces.EDITOR),
    iconId = "code",
    visible = { ctx -> linkAtLine(ctx) != null },
) { ctx ->
    val link = linkAtLine(ctx) ?: return@SimpleAction ActionResult.message("Nothing here is bound over JNI")
    ActionResult.effect(ActionEffect.OpenFile(link.targetPath, link.targetOffset))
}

private fun linkAtLine(ctx: ActionContext): JniLink? {
    val path = ctx.activeFilePath ?: return null
    if (JNI_FILE_SUFFIXES.none { path.endsWith(it) }) return null
    val text = ctx.documentText ?: return null
    val at = ctx.selectionStart ?: return null
    val module = JniModules.forPath(path) ?: return null
    val line = JniLinks.lineOf(text, at)
    return runCatching { JniLinks.linksIn(module, path, text) }.getOrDefault(emptyList())
        .firstOrNull { JniLinks.lineOf(text, it.nameStart) == line }
}

/** Go to Declaration across the JNI boundary (SPI 3.1.0): from a native method to its C/C++ body, and back. */
class JniDeclarationProvider : DeclarationProvider {
    override val id = "ndk.jni.declaration"
    override val languages = setOf(
        LanguageId("java"), LanguageId("kotlin"), LanguageId(dev.codeassist.ndk.NdkPlugin.C_LANGUAGE),
        LanguageId(dev.codeassist.ndk.NdkPlugin.CPP_LANGUAGE),
    )

    override suspend fun declarations(target: AnalysisTarget, offset: Int): List<NavigationTarget> {
        JniModules.remember(target.module)
        val text = target.parsed.text().toString()
        return JniLinks.linksIn(target.module, target.file.path, text)
            .filter { offset in it.nameStart..it.nameEnd }
            .map { NavigationTarget(it.targetPath, it.targetOffset, it.label) }
    }
}

/**
 * Registers [JniDeclarationProvider]. A class of its own so that a host without SPI 3.1.0 never loads it:
 * the provider names types that host does not have, and is only reached after [HostSupport.spi31] said yes.
 */
object JniDeclarations {
    fun register(reg: dev.ide.plugin.PluginRegistration) {
        reg.register(dev.ide.analysis.DECLARATION_PROVIDER_EP, JniDeclarationProvider())
    }
}
