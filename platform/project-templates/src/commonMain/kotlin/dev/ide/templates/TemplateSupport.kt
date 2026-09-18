// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.templates

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold

/**
 * The Java/Kotlin module type the built-in templates scaffold against.
 *
 * Deliberately empty: a module type is an identity plus what it defaults to, and these templates declare
 * their own source sets. Richer types (Android app/library) ship with the host that can build them.
 */
object JavaLibModuleType : ModuleType {
    override val id = "java-lib"
    override val displayName = "Java Library"
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/** Helpers every built-in template shares. */
object TemplateSupport {
    /** "com.example.app" -> "com/example/app". */
    fun pkgPath(pkg: String): String = pkg.replace('.', '/')

    /** A safe PascalCase type name derived from a free-form project name (fallback "App"). */
    fun typeName(raw: String): String {
        val cleaned = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val candidate = cleaned.ifEmpty { "App" }
        return if (candidate.first().isDigit()) "App$candidate" else candidate
    }

    /** A `main` source set rooted at [sourceDir] (`src/main/java` or `src/main/kotlin`). */
    fun mainSources(sourceDir: String) =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf(sourceDir to setOf(ContentRole.SOURCE)))

    /**
     * Add a single-module project ([moduleName] of [typeId], sources under [sourceDir]) and commit it.
     *
     * Two transactions, not one: a module is added to a PROJECT, and the project does not exist until the
     * workspace transaction above it has committed.
     */
    fun singleModule(
        scaffold: ProjectScaffold,
        projectName: String,
        moduleName: String,
        typeId: String,
        sourceDir: String,
    ) {
        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(moduleName, scaffold.moduleType(typeId)).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(mainSources(sourceDir))
            }
            commit()
        }
    }
}
