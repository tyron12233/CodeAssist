package dev.ide.core

import dev.ide.analysis.MODULE_ANALYSIS
import dev.ide.build.BUILD_CONTROL
import dev.ide.core.services.SearchService
import dev.ide.index.SYMBOL_SEARCH
import dev.ide.model.MODULE_SOURCES
import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The four engine services promoted into the published api modules resolve under their SPI keys, through the
 * same `Workspace.service` / `Module.service` lookups a plugin's extension-point callback uses.
 *
 * Each SPI key is an alias registered alongside the engine's own internal key, so what comes back has to be
 * the engine's live instance rather than a second one built beside it: a plugin that starts a build and the
 * IDE's own console must be looking at the same build.
 */
class PromotedServiceKeysTest {

    @Test
    fun theSpiKeysResolveTheEnginesOwnServiceInstances() {
        withTempDir("promoted-keys") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val workspace = ide.store.workspace
                val container = ide.store.workspaceContainer

                assertSame(
                    container.getService(BUILD_SERVICE),
                    workspace.service(BUILD_CONTROL),
                    "BUILD_CONTROL must alias the engine's build service, not a second instance",
                )
                assertSame(
                    container.getService(SEARCH_SERVICE),
                    workspace.service(SYMBOL_SEARCH) as SearchService,
                    "SYMBOL_SEARCH must alias the engine's search service",
                )
                assertSame(
                    container.getService(MODULE_SERVICE),
                    workspace.service(MODULE_SOURCES),
                    "MODULE_SOURCES must alias the engine's module service",
                )

                // MODULE scope: one instance per module, reached from the Module a callback is handed.
                val module = ide.modules().first()
                assertSame(
                    module.service(MODULE_ANALYZERS),
                    module.service(MODULE_ANALYSIS),
                    "MODULE_ANALYSIS must alias that module's analyzer service",
                )
            }
        }
    }
}
