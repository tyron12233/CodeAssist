// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lang

import dev.ide.platform.ExtensionPoint
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.vfs.VirtualFile

/**
 * The half of the language SPI that is typed BY THE PROJECT MODEL, and therefore stays on the JVM.
 *
 * A compilation context is built from a `Workspace` and a `Module` and carries a `ClasspathSnapshot`: a real
 * build over a real filesystem. The rest of the SPI (`LanguageId`, `SourceAnalyzer`, the DOM, completion,
 * resolve) has no such dependency and is in `commonMain`, which is what lets a language backend's EDITOR half
 * be compiled for a non-JVM target while the part that binds it to a build does not have to be.
 */

/**
 * Built FROM the project model. [classpath] is the same hashed ClasspathSnapshot the build uses, so
 * api/implementation correctness and cache-invalidation-on-classpath-change are inherited, not
 * re-derived. Changing this context's fingerprint invalidates the analyzer's caches.
 */
interface CompilationContext {
    val sourceRoots: List<VirtualFile>

    /**
     * The dependency path, JVM-shaped by default because that is what the host can derive from the model on
     * its own. A language with no classpath leaves it [ClasspathSnapshot.EMPTY] and carries what its own
     * toolchain needs as an [attribute], or as entries under a [dev.ide.model.ClasspathEntryKind] of its own.
     */
    val classpath: ClasspathSnapshot get() = ClasspathSnapshot.EMPTY

    /** The platform SDK's path. [ClasspathSnapshot.EMPTY] for a language with no boot classpath. */
    val bootClasspath: ClasspathSnapshot get() = ClasspathSnapshot.EMPTY

    /** Defaults to [LanguageLevel.DEFAULT]; a language with its own versioning names its own level. */
    val languageLevel: LanguageLevel get() = LanguageLevel.DEFAULT

    /** Where compilation output lands, or null for a language that produces none. */
    val outputDir: VirtualFile? get() = null

    /** JVM annotation processors. Empty for everything that has no such notion. */
    val processors: List<AnnotationProcessor> get() = emptyList()

    /**
     * A language-specific input the core has no name for: an interpreter path, a virtualenv, include
     * directories, a sysroot. The provider that built this context is what puts one here, and the backend
     * that reads it is the same plugin, so the two agree on the key without the core knowing it exists.
     * Answers null for a key this context carries no value for, which is the normal case.
     */
    fun <T : Any> attribute(key: ContextKey<T>): T? = null

    /**
     * Source attachments for the classpath libraries (e.g. Maven `-sources.jar`s). NOT compiled — they exist
     * so editor features can recover parameter names and javadoc that the binary classes don't carry. Empty
     * by default; the JDK `src.zip` and Android platform sources are derived by the backend from the boot
     * classpath rather than listed here.
     */
    val sourceAttachments: List<VirtualFile> get() = emptyList()
}

/**
 * Typed key for a [CompilationContext.attribute]. Like [dev.ide.model.FacetKey] it has **reference
 * identity**, so the plugin that writes an attribute and the backend that reads it must name the same `val`;
 * [id] exists for diagnostics, not for matching.
 */
class ContextKey<T : Any>(val id: String) {
    override fun toString(): String = id
}

/**
 * Builds the [CompilationContext] for a module whose analysis inputs the host cannot derive.
 *
 * Without one, every backend is handed the context the host assembles from the project model, which is the
 * JVM reading of a module: a classpath walked with `api`/`implementation` export semantics, a platform SDK
 * boot classpath, a Java language level. That is right for the languages the IDE ships and wrong for a
 * language whose inputs are a virtualenv, an include path or a sysroot, which no amount of model-walking
 * produces.
 *
 * The host asks each provider claiming the language, in registration order, and uses the first non-null
 * answer; returning null means "not mine after all", and falls back to the model-derived context. A provider
 * is free to start from that context and add to it, which is the usual case for a language that does have a
 * classpath but needs something extra alongside it.
 */
interface CompilationContextProvider : LanguageScoped {
    /**
     * The context to analyze [module] in [language] with, or null to leave it to the host. [variant] is the
     * active build-variant config-name set, as passed to [dev.ide.model.Module.classpath]. Must not mutate
     * the model, and is called on the analysis dispatcher, so it should not block on the network.
     */
    fun contextFor(
        workspace: Workspace,
        module: Module,
        language: LanguageId,
        variant: Set<String>?,
    ): CompilationContext?
}

/**
 * language-api — the seam for "own parser / Eclipse JDT / a custom frontend". The core never depends on a
 * concrete parser; it depends on [LanguageBackend] and the backend-neutral DOM/resolve/completion types.
 * The project model supplies the [CompilationContext] (roots + classpath + language level); the backend
 * produces ASTs, diagnostics, resolution, and completion.
 *
 * **Editor-side only.** Emitting bytecode is the build system's job: each language module owns its own
 * build compile task (lang-jdt drives ecj, lang-kotlin drives K2) and the build graph calls it directly.
 * The LanguageBackend is therefore not a compiler factory; it never sees the build.
 *
 * Recommended wiring: JDT is the default analyzer + completion backend (error recovery, working-copy
 * reconcile, a built-in completion engine, light on ART); a custom parser slots into the same interfaces.
 */
interface LanguageBackend : LanguageScoped {
    val id: String                          // "jdt" | "kotlin" | "xml" | "custom"

    /** The languages this backend claims. Unlike a cross-cutting [LanguageScoped] extension, a backend
     *  claiming nothing is never selected: it must name what it parses. */
    override val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>

    /** Editor-time: tolerant parsing, resolution, completion. */
    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer
}

/** Plugins contribute context providers here; the host consults them before its own model-derived context. */
val COMPILATION_CONTEXT_PROVIDER_EP =
    ExtensionPoint<CompilationContextProvider>("platform.compilationContext")

interface AnnotationProcessor {
    val qualifiedName: String
    val classpath: ClasspathSnapshot
}

/**
 * The extension point through which language backends are contributed. The host (ide-core) selects a
 * backend per file by matching the file's [LanguageId] against each backend's [LanguageBackend.languages],
 * so a new language is added by registering one more backend, not by editing the host. Backends register in
 * plugin order; the host picks the first whose `languages` contains the id.
 */
val LANGUAGE_BACKEND_EP = ExtensionPoint<LanguageBackend>("platform.languageBackend")
