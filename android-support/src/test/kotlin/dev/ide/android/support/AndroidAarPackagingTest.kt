package dev.ide.android.support

import dev.ide.android.support.tools.AarMetadata
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `assembleAar` for an `android-lib` target packages a spec-faithful `.aar`: `classes.jar` of the library's
 * own code, its `AndroidManifest.xml`, the raw `res/`, the `R.txt` symbol table, and the AGP
 * `aar-metadata.properties`. Uses the multi-module sample's `feature` library. Skipped when no SDK.
 */
class AndroidAarPackagingTest {

    private object JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    @Test
    fun theAndroidLibraryAssemblesToAnAar() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-aar")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            types.register(JavaLib, PluginId("java-support"))
            AndroidSupport.register(types, FacetCodecRegistry())

            SampleAndroidProject.generate(store, types.resolve("android-app"), types.resolve("android-lib"), types.resolve("java-lib"))

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(sdk, signing)
            val project = store.workspace.projects.single { it.name == SampleAndroidProject.PROJECT }

            // Assemble the android-lib TARGET (not the app) → an .aar.
            val graph = buildSystem.createBuildGraph(
                project, BuildRequest(listOf(ModuleId("feature")), VariantSelector("debug"), BuildGoal.ASSEMBLE),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "android-lib AAR assembly failed:\n$log")

            val aar = dir.resolve("feature/build/outputs/aar/feature-debug.aar")
            assertTrue(Files.isRegularFile(aar), "AAR not produced at $aar\n$log")
            ZipFile(aar.toFile()).use { zf ->
                val entries = zf.entries().toList().map { it.name }.toSet()
                assertTrue("classes.jar" in entries, "AAR missing classes.jar: $entries")
                assertTrue("AndroidManifest.xml" in entries, "AAR missing AndroidManifest.xml: $entries")
                assertTrue("R.txt" in entries, "AAR missing R.txt: $entries")
                assertTrue(entries.any { it.startsWith("res/") }, "AAR missing res/: $entries")
                assertTrue(AarMetadata.ENTRY_PATH in entries, "AAR missing aar-metadata.properties: $entries")

                // The library's own class is inside classes.jar (and the compile-only R is NOT).
                val innerJar = zf.getInputStream(zf.getEntry("classes.jar")).readBytes()
                val innerEntries = ZipInputStream(innerJar.inputStream()).use { zis ->
                    generateSequence { zis.nextEntry }.map { it.name }.toList()
                }
                assertTrue(innerEntries.any { it.contains("com/example/feature/FeatureText") }, "classes.jar missing FeatureText: $innerEntries")

                // R.txt lists the library's own resource symbols.
                val rtxt = zf.getInputStream(zf.getEntry("R.txt")).readBytes().toString(Charsets.UTF_8)
                assertTrue("feature_title" in rtxt, "R.txt should list the lib's resources: $rtxt")
            }
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }
}
