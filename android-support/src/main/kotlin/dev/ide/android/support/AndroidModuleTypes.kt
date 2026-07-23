package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate

/**
 * The Android module types contributed by android-support. `android-app` produces a
 * signed APK; `android-lib` produces an AAR. Both lay down the conventional Android source-set layout
 * (`src/<set>/{java,kotlin,res,assets,resources,jniLibs}`) and attach a default [AndroidFacet] so a freshly-created module is
 * immediately buildable; the project-structure UI then edits the facet to set the real namespace/SDKs.
 *
 * Only the native build system builds these (the Gradle-compat layer maps `com.android.*` plugins onto
 * the same types during sync).
 */
sealed class AndroidModuleType(
    final override val id: String,
    final override val displayName: String,
    private val application: Boolean,
) : ModuleType {

    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)

    /** `main` plus one source set per default build type, each with java/res/assets roots. */
    override fun defaultSourceSets(): List<SourceSetTemplate> = buildList {
        add(sourceSet("main"))
        AndroidFacet.DEFAULT_BUILD_TYPES.forEach { add(sourceSet(it.name)) }
    }

    /** A starter [AndroidFacet] — placeholder namespace/SDK the wizard overrides. */
    override fun defaultFacets(): List<FacetTemplate> = listOf(
        FacetTemplate(
            AndroidFacet.KEY,
            AndroidFacetCodec.encode(
                AndroidFacet(
                    namespace = "com.example.${id.substringAfter('-')}",
                    compileSdk = DEFAULT_COMPILE_SDK,
                    minSdk = DEFAULT_MIN_SDK,
                    isApplication = application,
                ),
            ),
        ),
    )

    private fun sourceSet(name: String) = SourceSetTemplate(
        name = name,
        scope = DependencyScope.IMPLEMENTATION,
        roots = linkedMapOf(
            "src/$name/java" to setOf(ContentRole.SOURCE),
            "src/$name/kotlin" to setOf(ContentRole.SOURCE),   // Kotlin sources compile via compileKotlin
            "src/$name/res" to setOf(ContentRole.ANDROID_RES),
            "src/$name/assets" to setOf(ContentRole.ASSETS),
            "src/$name/resources" to setOf(ContentRole.RESOURCE),  // Java resources → merged into the APK root
            "src/$name/jniLibs" to setOf(ContentRole.JNI_LIBS),    // prebuilt `<abi>/*.so` → packaged under lib/
        ),
    )

    companion object {
        const val DEFAULT_COMPILE_SDK = 34
        // 26, not 21: below API 26 D8 must desugar (lambdas/default-interface-methods/core-library) every
        // library on device, and the whole-set desugaring cache key means adding a dependency re-dexes the
        // entire classpath. At 26+ desugaring is off and each library dexes once into a cross-project bucket,
        // so a new Compose project's first build is far cheaper and later builds are cache hits. New projects
        // default here; importing an existing project keeps its declared minSdk (AndroidFacet default stays 21).
        const val DEFAULT_MIN_SDK = 26
    }
}

object AndroidAppModuleType : AndroidModuleType("android-app", "Android Application", application = true)

object AndroidLibModuleType : AndroidModuleType("android-lib", "Android Library", application = false)
