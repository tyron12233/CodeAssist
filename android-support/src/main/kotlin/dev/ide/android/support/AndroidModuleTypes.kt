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
 * (`src/<set>/{java,res,assets}`) and attach a default [AndroidFacet] so a freshly-created module is
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
        ),
    )

    companion object {
        const val DEFAULT_COMPILE_SDK = 34
        const val DEFAULT_MIN_SDK = 21
    }
}

object AndroidAppModuleType : AndroidModuleType("android-app", "Android Application", application = true)

object AndroidLibModuleType : AndroidModuleType("android-lib", "Android Library", application = false)
