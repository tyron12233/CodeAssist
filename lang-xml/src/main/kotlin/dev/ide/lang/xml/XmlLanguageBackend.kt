package dev.ide.lang.xml

import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer

/**
 * The XML language backend (Android layouts, values, manifest, drawables, menus, navigation). Analysis +
 * completion only: XML is not compiled here — `res/` is processed by the Android build system (aapt2), so
 * this backend contributes no compiler. Error recovery is inherent to the tolerant parser; bindings/
 * incremental are not advertised (no symbol model yet; full reparse per change).
 */
class XmlLanguageBackend : LanguageBackend {
    override val id: String = "xml"
    override val languages: Set<LanguageId> = setOf(LANGUAGE_ID)
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.COMPLETION,
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = XmlSourceAnalyzer()

    companion object {
        val LANGUAGE_ID = LanguageId("xml")
    }
}
