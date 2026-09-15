package dev.codeassist.ndk

import dev.ide.model.ContentRole
import dev.ide.model.MODULE_SOURCES
import dev.ide.model.Module
import dev.ide.model.ModuleSources
import dev.ide.platform.log.Logger

/**
 * Declares a module's C and C++ directories to the project model.
 *
 * Without this the plugin's whole editor half is dead in a conventionally laid out project, and silently so.
 * The host resolves a file to its module by asking which **declared content root** contains it, and only
 * roots carrying [ContentRole.SOURCE] or `GENERATED` count (`IdeServices.sourceRoots`). Nothing declares
 * `src/main/cpp`: the android-app and java-lib module types know about `src/main/java`, `res`, `assets`,
 * `jniLibs` and not about C++. So a `.cpp` there belongs to no module, gets no analysis target, and is
 * invisible in the navigator — which renders the declared roots, not the file system.
 *
 * The role is [ContentRole.SOURCE] rather than a `cpp` role of the plugin's own, and that is not laziness:
 * a custom role would persist and round-trip perfectly and still be ignored by the two things that matter,
 * because the host's rule names SOURCE specifically. The compile pipelines glob by extension, so a `.cpp`
 * sitting in a source root is not picked up by javac or kotlinc.
 */
object NdkSourceRoots {

    /**
     * Ensure every directory [modules]' facets name is a declared source root.
     *
     * Takes modules rather than a service lookup, and that is forced rather than preferred. [MODULE_SOURCES]
     * is WORKSPACE-scoped, and a plugin's `PluginRegistration.appServices` is the APPLICATION container, so
     * it resolves to null there. The only way an engine facet reaches a workspace service is through an
     * extension point that hands it a model object — `Module.service` walks module, then workspace, then
     * application. The project-lifecycle topic looks like the natural hook and is not one: its event carries
     * a root path and nothing to resolve against.
     *
     * So this is called from the build plugin, where `BuildConfiguration.project.modules` are real modules.
     * It makes the first build of an existing project repair its own layout; a project made from the
     * template already has the root declared and never needs it.
     *
     * Idempotent and cheap: [ModuleSources.addSourceRoot] is only reached for a directory that is not
     * already a root.
     */
    fun ensureFor(modules: List<Module>, log: Logger) {
        for (module in modules) {
            val facet = module.facets.get(NdkFacet.KEY) ?: continue
            val sources = runCatching { module.service(MODULE_SOURCES) }.getOrNull() ?: continue
            val declared = module.sourceSets
                .flatMap { it.contentRoots }
                .mapTo(HashSet()) { it.dir.path.trimEnd('/') }

            for (relative in facet.sourceDirs) {
                val absolute = "${module.dir.path.trimEnd('/')}/${relative.trim('/')}"
                if (absolute in declared) continue
                val (sourceSet, dirName) = split(relative)
                val created = runCatching {
                    sources.addSourceRoot(module.name, sourceSet, dirName, setOf(ContentRole.SOURCE))
                }.onFailure { log.warn("could not declare $relative on ${module.name}", it) }.getOrNull()
                if (created != null) log.info("declared ${module.name}/$relative as a source root")
            }
        }
    }

    /**
     * Split a module-relative directory into the source set that owns it and the name under that set.
     *
     * `addSourceRoot` creates `<src/SET>/<dirName>`, so `src/main/cpp` has to arrive as `main` + `cpp` and
     * not as one opaque path. A directory laid out some other way (`native/`, `jni/`) has no source set to
     * belong to, and is attached to `main`, which is the set every variant builds.
     */
    internal fun split(relative: String): Pair<String, String> {
        val parts = relative.trim('/').split('/')
        return if (parts.size >= 3 && parts[0] == "src") parts[1] to parts.drop(2).joinToString("/")
        else "main" to relative.trim('/')
    }
}
