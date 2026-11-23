package dev.ide.build.split

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/** `toolchainSplit { ... }` in the app module. */
abstract class ToolchainSplitExtension {
    /** Internal-name prefixes of the classes that leave R8's input, e.g. `org/jetbrains/kotlin/`. */
    abstract val packages: ListProperty<String>

    /** Build types it applies to; each must have minification on. */
    abstract val buildTypes: SetProperty<String>

    /** The R8/D8 version that dexes the toolchain: the one AGP shrinks with (the root buildscript's pin). */
    abstract val d8Version: Property<String>

    /** Version of `com.android.tools:desugar_jdk_libs_configuration`, matching the app's coreLibraryDesugaring. */
    abstract val desugarConfigVersion: Property<String>

    /** Heap for the D8 JVM. It never runs alongside R8 (see [ToolchainSplitPlugin]). */
    abstract val d8MaxHeap: Property<String>
}

/**
 * Takes the bundled toolchain out of R8's input for the minified build types.
 *
 * R8 spends most of a release build reading, tracing and rewriting the Kotlin compiler, IntelliJ, JDT and R8
 * itself, which the keep rules then leave almost untouched, and needs more than 3 GB of heap to hold them.
 * With this plugin those classes bypass R8: [SplitToolchainClassesTask] replaces the classes R8 receives,
 * the toolchain goes on R8's classpath (so the app can still link against it), and generated keep rules
 * protect what the toolchain uses in the rest of the app.
 *
 * Apply with `id("dev.ide.toolchain-split")`.
 */
class ToolchainSplitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("toolchainSplit", ToolchainSplitExtension::class.java)
        ext.d8MaxHeap.convention("3g")
        val d8 = project.configurations.create("toolchainSplitD8") {
            isCanBeConsumed = false
            isTransitive = false
        }
        project.dependencies.addProvider(d8.name, ext.d8Version.map { "com.android.tools:r8:$it" })
        val desugarConfig = project.configurations.create("toolchainSplitDesugarConfig") {
            isCanBeConsumed = false
            isTransitive = false
        }
        project.dependencies.addProvider(
            desugarConfig.name,
            ext.desugarConfigVersion.map { "com.android.tools:desugar_jdk_libs_configuration:$it" },
        )
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            val android = project.extensions.getByType(ApplicationExtension::class.java)
            components.onVariants { variant ->
                if (variant.buildType !in ext.buildTypes.get()) return@onVariants
                // Without R8 (-Pcodeassist.r8=false) the toolchain is dexed with everything else already.
                if (!variant.isMinifyEnabled) return@onVariants
                val cap = variant.name.replaceFirstChar { it.uppercase() }
                val outDir = project.layout.buildDirectory.dir("intermediates/toolchain-split/${variant.name}")

                val split = project.tasks.register("split${cap}ToolchainClasses", SplitToolchainClassesTask::class.java) {
                    toolchainPackages.set(ext.packages)
                    toolchainJar.set(outDir.map { it.file("toolchain.jar") })
                    keepRules.set(outDir.map { it.file("toolchain-keep-rules.pro") })
                }
                variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                    .use(split)
                    .toTransform(
                        ScopedArtifact.CLASSES,
                        SplitToolchainClassesTask::allJars,
                        SplitToolchainClassesTask::allDirectories,
                        SplitToolchainClassesTask::programJar,
                    )
                variant.proguardFiles.add(split.flatMap { it.keepRules })

                // R8 must still resolve the toolchain the app links against, on its classpath (where a class
                // may extend a program class) rather than as a library (where R8 rejects that). AGP exposes
                // its classpath only as the internal R8Task's `referencedClasses`, so it is set by name.
                project.tasks.matching { it.name == "minify${cap}WithR8" }.configureEach {
                    val referenced = property("referencedClasses") as ConfigurableFileCollection
                    referenced.from(split.flatMap { it.toolchainJar })
                }

                val dex = project.tasks.register("dex${cap}Toolchain", DexToolchainTask::class.java) {
                    toolchainJar.set(split.flatMap { it.toolchainJar })
                    d8Classpath.from(d8)
                    desugarConfigJar.from(desugarConfig)
                    bootClasspath.from(components.sdkComponents.bootClasspath)
                    minApi.set(variant.minSdk.apiLevel)
                    maxHeap.set(ext.d8MaxHeap)
                    dexDir.set(outDir.map { it.dir("dex") })
                    // Both are big JVMs; one at a time keeps the build inside this machine's memory.
                    mustRunAfter("minify${cap}WithR8")
                }

                val buildType = android.buildTypes.getByName(variant.buildType!!)
                val add = project.tasks.register("add${cap}ToolchainDex", AddToolchainDexTask::class.java) {
                    dexDir.set(dex.flatMap { it.dexDir })
                    val signing = buildType.signingConfig
                        ?: error("${variant.name}: the toolchain split re-signs the APK and needs a signing config")
                    storeFile.set(project.layout.file(project.provider { signing.storeFile }))
                    storeType.set(project.provider { signing.storeType })
                    storePassword.set(project.provider { signing.storePassword })
                    keyAlias.set(project.provider { signing.keyAlias })
                    keyPassword.set(project.provider { signing.keyPassword })
                    v1.set(variant.signingConfig.enableV1Signing)
                    v2.set(variant.signingConfig.enableV2Signing)
                    v3.set(variant.signingConfig.enableV3Signing)
                    minSdk.set(variant.minSdk.apiLevel)
                }
                val request = variant.artifacts.use(add)
                    .wiredWithDirectories(AddToolchainDexTask::apkIn, AddToolchainDexTask::apkOut)
                    .toTransformMany(SingleArtifact.APK)
                add.configure { transformationRequest.set(request) }

                // The .aab gets the same dex, or Play would build APKs without the toolchain.
                val addToBundle = project.tasks.register(
                    "add${cap}ToolchainDexToBundle", AddToolchainDexToBundleTask::class.java,
                ) {
                    dexDir.set(dex.flatMap { it.dexDir })
                    val signing = buildType.signingConfig
                        ?: error("${variant.name}: the toolchain split re-signs the bundle and needs a signing config")
                    storeFile.set(project.layout.file(project.provider { signing.storeFile }))
                    storeType.set(project.provider { signing.storeType })
                    storePassword.set(project.provider { signing.storePassword })
                    keyAlias.set(project.provider { signing.keyAlias })
                    keyPassword.set(project.provider { signing.keyPassword })
                }
                variant.artifacts.use(addToBundle)
                    .wiredWithFiles(AddToolchainDexToBundleTask::bundleIn, AddToolchainDexToBundleTask::bundleOut)
                    .toTransform(SingleArtifact.BUNDLE)
            }
        }
    }
}
