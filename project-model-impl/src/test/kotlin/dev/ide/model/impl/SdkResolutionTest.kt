package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.PlatformKind
import dev.ide.model.SdkDependency
import dev.ide.model.SdkRef
import dev.ide.model.SdkResolution
import dev.ide.platform.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core of the platform-SDK split: a `java-*` module resolves the JVM (core-Java) SDK, an `android-*`
 * module the Android SDK — from ONE workspace SDK table — and an explicit `module.sdk` override wins. This
 * is what keeps a console app free of `android.*` (it never resolves the android.jar boot classpath).
 */
class SdkResolutionTest {

    private fun seedTwoSdks(store: ProjectModelStore) = store.replaceSdks(
        listOf(
            SdkData("android", listOf("/sdk/android.jar"), buildToolsPath = "/sdk/bt/34", kind = PlatformKind.ANDROID),
            SdkData("core-java", listOf("/cache/core-java.jar"), buildToolsPath = null, kind = PlatformKind.JVM),
        ),
    )

    private fun module(store: ProjectModelStore, name: String): Module =
        store.workspace.projects.single().modules.single { it.name == name }

    @Test
    fun resolvesByModuleTypePlatform() = withWorkspace { platform, store ->
        val types = ModuleTypeRegistry(platform.extensions)
        types.register(TestModuleType("android-app"), PluginId("android-support"))
        seedTwoSdks(store)
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", types.resolve("java-lib"))
            addModule("app", types.resolve("android-app"))
            commit()
        }

        // A java-lib gets the JVM/core-Java platform; an android-app gets the Android platform — no android.jar
        // for the console module, no core-java for the android one.
        assertEquals("core-java", SdkResolution.sdkFor(store.workspace, module(store, "lib"))?.name)
        assertEquals("android", SdkResolution.sdkFor(store.workspace, module(store, "app"))?.name)
    }

    @Test
    fun explicitSdkOverrideWins() = withWorkspace { platform, store ->
        val types = ModuleTypeRegistry(platform.extensions)
        seedTwoSdks(store)
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", types.resolve("java-lib")).apply { sdk = SdkRef("android") }
            commit()
        }
        // The module type says JVM, but the explicit override pins the Android SDK — and it round-trips.
        assertEquals("android", SdkResolution.sdkFor(store.workspace, module(store, "lib"))?.name)
        store.save()
        val reopened = ProjectModel.open(store.rootPath, platform, FacetCodecRegistry())
        assertEquals("android", reopened.workspace.projects.single().modules.single { it.name == "lib" }.sdk?.name)
    }

    @Test
    fun sdkDependencyEntryResolvesWhenNoOverride() = withWorkspace { platform, store ->
        val types = ModuleTypeRegistry(platform.extensions)
        seedTwoSdks(store)
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", types.resolve("java-lib")).apply { addDependency(SdkDependency(SdkRef("android"))) }
            commit()
        }
        // Legacy explicit form: an SdkDependency naming the Android SDK is honored over the JVM type default.
        assertEquals("android", SdkResolution.sdkFor(store.workspace, module(store, "lib"))?.name)
    }
}
