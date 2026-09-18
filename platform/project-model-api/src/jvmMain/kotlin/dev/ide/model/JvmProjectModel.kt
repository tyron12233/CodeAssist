package dev.ide.model

import dev.ide.platform.ServiceScope
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The parts of the model typed by a JVM notion rather than by the model itself.
 *
 * `ServiceScope` belongs to the service container in `:platform-core`, whose message bus is keyed by
 * `java.lang.Class`; a `Path` is a real filesystem. Everything the model IS — a workspace of projects of
 * modules of source sets, and the transactions that author them — stays in common code, which is what lets
 * a project TEMPLATE be written once and run on every host.
 */

/** The [Module] a MODULE-scoped service factory is bound to. */
fun ServiceScope.module(): Module = scopeObject as? Module
    ?: error("module() is only valid in a MODULE-scoped service (scope=$level)")

/** The [Workspace] above a workspace- or module-scoped service factory. */
fun ServiceScope.workspace(): Workspace = getService(WORKSPACE_SERVICE)

/**
 * This module's own content-root directories carrying [role], across every source set and with no dependency
 * walk. Empty for a module that declares none, which is the honest answer for a role its module type has no
 * notion of.
 *
 * The `main` source set's roots come FIRST, the rest following in declaration order, because a caller
 * generating a file takes the first and `main` is the only source set every variant builds. Declaration
 * order alone puts a variant's roots (`src/debug/res`, ahead of `src/main/res` on the android-app module
 * type) at the front, and a file written there is missing from every build that does not select that
 * variant.
 *
 * The role is a parameter rather than a fixed set because [ContentRole] is open: a plugin that contributes a
 * module type asks about its own role here the same way the IDE asks about [ContentRole.ANDROID_RES].
 */
fun Module.contentRootsFor(role: ContentRole): List<Path> =
    sourceSets.sortedBy { if (it.name == "main") 0 else 1 } // stable sort: ties keep their declaration order
        .flatMap { it.contentRoots }
        .filter { role in it.roles }
        .mapNotNull { runCatching { Paths.get(it.dir.path) }.getOrNull() }
