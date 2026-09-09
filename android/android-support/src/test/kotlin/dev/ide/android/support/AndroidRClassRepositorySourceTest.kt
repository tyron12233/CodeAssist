package dev.ide.android.support

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceModel
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.Module
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.Workspace
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.testkit.TestEnv
import dev.ide.testkit.testEnv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the synthetic `R` gets its merged resources from.
 *
 * The repository is expensive to build (a module's own `res/` plus every dependency's), so a host publishes
 * one cache per workspace under [ANDROID_RESOURCE_REPOSITORY] and the provider reads it. The point of these
 * tests is *which* workspace's cache it reads: the one that arrives with the call, never a host-global
 * "currently open project" handle. With a global, an `R` generated for one workspace while another was the
 * active one resolved against the wrong project's resources, and module ids collide across projects
 * (every Android project has an `app`), so the mix-up was silent.
 */
class AndroidRClassRepositorySourceTest {

    private fun repoOf(vararg strings: String) =
        ResourceRepository(items = strings.map { ResourceItem(ResourceType.STRING, it) })

    /** A model that always parses to [repo], standing in for reading `res/` off disk. */
    private fun modelOf(repo: ResourceRepository) = object : ResourceModel {
        override fun parse(resDirs: List<Path>, textOverride: (Path) -> String?): ResourceRepository = repo
    }

    /** An opened workspace with one `android-app` module named `app` under [namespace]. */
    private fun open(env: TestEnv, name: String, namespace: String): Pair<ProjectModelStore, Module> {
        val dir = Files.createDirectories(env.dir.resolve(name))
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(ModuleTypeRegistry(env.platform.extensions), codecs)
        val store = ProjectModel.open(dir, env.platform, codecs)
        store.workspace.beginModification().apply {
            addProject(name, BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", ModuleTypeRegistry(env.platform.extensions).resolve("android-app")).apply {
                putFacet(AndroidFacet(namespace = namespace, compileSdk = 34))
            }
            commit()
        }
        return store to store.workspace.projects.single().modules.first { it.name == "app" }
    }

    /** The `R.string` field names the provider emits for [module] of [workspace]. */
    private fun stringsFor(
        module: Module,
        workspace: Workspace,
        namespace: String,
        model: ResourceModel,
    ): Set<String> {
        val ctx = object : SyntheticClassContext {
            override val module = module
            override val workspace = workspace
        }
        val r: SyntheticClass = AndroidRClassProvider(model).classesFor(ctx).single { it.fqName == "$namespace.R" }
        return r.nestedClasses.single { it.fqName.endsWith(".string") }.fields.map { it.name }.toSet()
    }

    @Test
    fun `the workspace's published repository source is preferred over parsing`() = testEnv("r-source") { env ->
        val (store, module) = open(env, "demo", "com.example.app")
        store.workspaceContainer.registerService(ANDROID_RESOURCE_REPOSITORY) {
            ResourceRepositorySource { _, _ -> repoOf("from_the_cache") }
        }
        assertEquals(
            setOf("from_the_cache"),
            stringsFor(module, store.workspace, "com.example.app", modelOf(repoOf("from_a_fresh_parse"))),
            "the provider must read the cache the workspace publishes, not re-parse",
        )
        store.close()
    }

    @Test
    fun `parsing is the fallback when the workspace publishes no source`() = testEnv("r-nosource") { env ->
        val (store, module) = open(env, "demo", "com.example.app")
        assertEquals(
            setOf("from_a_fresh_parse"),
            stringsFor(module, store.workspace, "com.example.app", modelOf(repoOf("from_a_fresh_parse"))),
            "with no cache registered the provider must still resolve, by parsing directly",
        )
        store.close()
    }

    @Test
    fun `each workspace resolves through its own source`() = testEnv("r-two") { env ->
        val (storeA, moduleA) = open(env, "alpha", "com.example.alpha")
        val (storeB, moduleB) = open(env, "beta", "com.example.beta")
        storeA.workspaceContainer.registerService(ANDROID_RESOURCE_REPOSITORY) {
            ResourceRepositorySource { _, _ -> repoOf("alpha_only") }
        }
        storeB.workspaceContainer.registerService(ANDROID_RESOURCE_REPOSITORY) {
            ResourceRepositorySource { _, _ -> repoOf("beta_only") }
        }
        val unused = modelOf(repoOf("never_parsed"))

        // Both workspaces are open at once, and both modules are called `app`. Each answer must come from
        // its own workspace: a host-global handle would give whichever was set last for both.
        assertEquals(setOf("alpha_only"), stringsFor(moduleA, storeA.workspace, "com.example.alpha", unused))
        assertEquals(setOf("beta_only"), stringsFor(moduleB, storeB.workspace, "com.example.beta", unused))
        assertEquals(
            setOf("alpha_only"),
            stringsFor(moduleA, storeA.workspace, "com.example.alpha", unused),
            "opening a second workspace must not change what the first one resolves",
        )
        storeA.close()
        storeB.close()
    }
}
