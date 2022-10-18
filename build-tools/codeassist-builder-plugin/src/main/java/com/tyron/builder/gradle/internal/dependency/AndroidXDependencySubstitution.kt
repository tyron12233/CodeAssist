package com.tyron.builder.gradle.internal.dependency

import com.android.tools.build.jetifier.core.config.ConfigParser
import com.android.tools.build.jetifier.processor.Processor
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Singleton object that maintains AndroidX mappings and configures AndroidX dependency substitution
 * rules.
 */
object AndroidXDependencySubstitution {

    /**
     * Mappings from old dependencies to AndroidX dependencies.
     *
     * Each entry maps "old-group:old-module" (without version) to
     * "new-group:new-module:new-version" (with version).
     */
    @JvmStatic
    val androidXMappings: Map<String, String> =
        Processor.createProcessor3(
            config = ConfigParser.loadDefaultConfig()!!,
            dataBindingVersion = "7.4"
        ).getDependenciesMap(filterOutBaseLibrary = false)

    /**
     * Replaces old support libraries with AndroidX.
     */
    fun replaceOldSupportLibraries(project: Project, reasonToReplace: String) {
        project.configurations.all { configuration ->
            // Apply the rules just before the configurations are resolved because too many rules
            // could significantly impact memory usage and build speed. (Many configurations are not
            // resolvable or resolvable but not actually resolved during a build.)
            if (!configuration.isCanBeResolved) {
                return@all
            }
            configuration.incoming.beforeResolve {
                configuration.resolutionStrategy.dependencySubstitution {
                    var mappings = androidXMappings
                    if (skipDataBindingBaseLibrarySubstitution(configuration)) {
                        mappings = mappings.filterNot { entry ->
                            entry.key == COM_ANDROID_DATABINDING_BASELIBRARY
                        }
                    }
                    if (skipAndroidArchDependencySubstitution(configuration)) {
                        mappings = mappings.filterNot { entry ->
                            entry.key.startsWith(ANDROID_ARCH_)
                        }
                    }
                    for (entry in mappings) {
                        // entry.key is in the form of "group:module" (without a version), and
                        // Gradle accepts that form.
                        it.substitute(it.module(entry.key))
                            .using(it.module(entry.value))
                            .withoutArtifactSelectors() // https://github.com/gradle/gradle/issues/5174#issuecomment-828558594
                            .because(reasonToReplace)
                    }
                }
            }
        }
    }

    /**
     * Returns `true` if the data binding base library (com.android.databinding:baseLibrary) should
     * not be replaced with AndroidX in the given configuration (see
     * https://issuetracker.google.com/78202536).
     *
     * Specifically:
     *   - When data binding is enabled, the annotation processor classpath will contain
     *     androidx.databinding:databinding-compiler, which (transitively) depends on
     *     com.android.databinding:baseLibrary. In that case, com.android.databinding:baseLibrary
     *     should not be replaced with AndroidX.
     *   - If com.android.databinding:baseLibrary appears in a configuration that is not an
     *     annotation processor classpath, then it should still be replaced with AndroidX.
     */
    private fun skipDataBindingBaseLibrarySubstitution(configuration: Configuration): Boolean {
        return configuration.name.endsWith("AnnotationProcessorClasspath")
                || configuration.name.startsWith("kapt")
    }

    /**
     * Returns `true` if the `android.arch.*:*:*` libraries should not be replaced with AndroidX in
     * the given configuration (see https://issuetracker.google.com/168038088).
     *
     * Specifically, for `android.arch.*:*:version`:
     *   - If version is 1.x.y: Replace it with AndroidX
     *   - Otherwise (e.g., if version is 2.x.y): Do not replace it with AndroidX as it is an
     *     invalid dependency, the user will need to correct it first.
     */
    private fun skipAndroidArchDependencySubstitution(configuration: Configuration): Boolean {
        return configuration.allDependencies.any {
            (it.group != null && it.group!!.startsWith(ANDROID_ARCH_))
                    && (it.version == null || !it.version!!.startsWith("1."))
        }
    }

    /**
     * Returns `true` if the given dependency (formatted as `group:name:version`) is an AndroidX
     * dependency.
     */
    fun isAndroidXDependency(dependency: String): Boolean {
        return dependency.startsWith(ANDROIDX)
                || dependency.startsWith(COM_GOOGLE_ANDROID_MATERIAL)
    }

    /**
     * Returns `true` if the given dependency (formatted as `group:name:version`) is a legacy
     * support library dependency.
     */
    fun isLegacySupportLibDependency(dependency: String): Boolean {
        return dependency.startsWith(COM_ANDROID_SUPPORT)
                || dependency.startsWith(COM_ANDROID_DATABINDING)
                || dependency.startsWith(ANDROID_ARCH_)
    }

    private const val ANDROIDX = "androidx"
    private const val COM_GOOGLE_ANDROID_MATERIAL = "com.google.android.material"

    private const val COM_ANDROID_SUPPORT = "com.android.support"
    private const val COM_ANDROID_DATABINDING = "com.android.databinding"
    internal const val COM_ANDROID_DATABINDING_BASELIBRARY = "com.android.databinding:baseLibrary"
    private const val ANDROID_ARCH_ = "android.arch."
}
