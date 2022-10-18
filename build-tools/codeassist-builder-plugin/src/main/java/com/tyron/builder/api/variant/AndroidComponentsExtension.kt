package com.tyron.builder.api.variant

import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.SdkComponents
import org.gradle.api.Action
import org.gradle.api.Incubating

/**
 * Generic extension for Android Gradle Plugin related components.
 *
 * Each component has a type, like application or library and will have a dedicated extension with
 * methods that are related to the particular component type.
 *
 * @param DslExtensionT the type of the DSL to be used in [DslLifecycle.finalizeDsl]
 * @param VariantBuilderT the [ComponentBuilder] type produced by this variant.
 * @param VariantT the [Variant] type produced by this variant.
 */
interface AndroidComponentsExtension<
        DslExtensionT: CommonExtension<*, *, *, *>,
        VariantBuilderT: VariantBuilder,
        VariantT: Variant>
    : DslLifecycle<DslExtensionT> {

    /**
     * [Action] based version of [finalizeDsl] above.
     */
    @Deprecated("Replaced by finalizeDsl", replaceWith = ReplaceWith("finalizeDsl(callback)"))
    fun finalizeDSl(callback: Action<DslExtensionT>)

    /**
//     * The version of the Android Gradle Plugin currently in use.
//     */
//    val pluginVersion: AndroidPluginVersion

    /**
     * Provides access to underlying Android SDK and build-tools components like adb.
     *
     * @return [SdkComponents] to access Android SDK used by Gradle.
     */
    @get:Incubating
    val sdkComponents: SdkComponents

    /**
     * Creates a [VariantSelector] instance that can be configured
     * to reduce the set of [ComponentBuilder] instances participating in the [beforeVariants]
     * and [onVariants] callback invocation.
     *
     * @return [VariantSelector] to select the variants of interest.
     */
    fun selector(): VariantSelector

    /**
     * Method to register a [callback] to be called with [VariantBuilderT] instances that
     * satisfies the [selector]. The [callback] will be called as soon as the [VariantBuilderT]
     * instance has been created but before any [com.tyron.builder.api.artifact.Artifact] has been
     * determined, therefore the build flow can still be changed when the [callback] is invoked.
     *
     * At this stage, access to the DSL objects is disallowed, use [finalizeDsl] method to
     * programmatically access the DSL objects before the [VariantBuilderT] object is built.
     *
     * Example without selection:
     * ```kotlin
     *  androidComponents {
     *      beforeVariants {
     *          println("Called with variant : ${'$'}name")
     *      }
     *  }
     * ```
     *
     * Example with selection:
     * ```kotlin
     *  androidComponents {
     *      val debug = selector().withBuildType("debug")
     *      beforeVariants(debug) {
     *          println("Called with variant : ${'$'}name")
     *      }
     *  }
     * ```
     *
     * @param selector [VariantSelector] instance to select which instance of [VariantBuilderT] are
     * of interest. By default, all instances are of interest.
     * @param callback lambda to be called with each instance of [VariantBuilderT] of interest.
     */
    fun beforeVariants(
        selector: VariantSelector = selector().all(),
        callback: (VariantBuilderT) -> Unit)

    /**
     * [Action] based version of [beforeVariants] above.
     */
    fun beforeVariants(
        selector: VariantSelector = selector().all(),
        callback: Action<VariantBuilderT>)

    /**
     * Allow for registration of a [callback] to be called with variant instances of type [VariantT]
     * once the list of [com.tyron.builder.api.artifact.Artifact] has been determined.
     *
     * At this stage, access to the DSL objects is disallowed and access to the [VariantBuilderT]
     * instance is limited to read-only access.
     *
     * Because the list of artifacts (including private ones) is final, one cannot change the build
     * flow anymore as [org.gradle.api.Task]s are now expecting those artifacts as inputs. However
     * users can modify such artifacts by replacing or transforming them, see [com.tyron.builder.api.artifact.Artifacts]
     * for details.
     *
     * Code executing in the [callback] also has access to the [VariantT] information which is used
     * to configure [org.gradle.api.Task] inputs (for example, the buildConfigFields). Such
     * information represented as [org.gradle.api.provider.Property] can still be modified ensuring
     * that all [org.gradle.api.Task]s created by the Android Gradle Plugin use the updated value.
     */
    fun onVariants(
        selector: VariantSelector = selector().all(),
        callback: (VariantT) -> Unit
    )

    /**
     * [Action] based version of [onVariants] above.
     */
    fun onVariants(
        selector: VariantSelector = selector().all(),
        callback: Action<VariantT>
    )

    /**
     * Register an Android Gradle Plugin DSL extension.
     *
     * Please see Gradle documentation first at :
     * https://docs.gradle.org/current/userguide/custom_plugins.html#sec:getting_input_from_the_build
     *
     * A lambda must be provided to create and configure the variant scoped object
     * that will be stored with the Android Gradle Plugin [com.tyron.builder.api.variant.Variant]
     * instance.
     *
     * Variant Scoped objects should use [org.gradle.api.provider.Property<T>] for its mutable
     * state to allow for late binding. (see [com.tyron.builder.api.variant.Variant] for examples).
     *
     * @param dslExtension the DSL extension configuration.
     * @param configurator a lambda to create a variant scoped object. The lambda is
     * provided with the [VariantExtensionConfig] that can be used to retrieve the [VariantT]
     * instance as well as DSL extensions registered with [DslExtension]
     * @return an sub type of [VariantExtension] instance that will be stored with the [VariantT]
     * instance and can be retrieved by [Variant.getExtension] API.
     */
    @Incubating
    fun registerExtension(
        dslExtension: DslExtension,
        configurator: (variantExtensionConfig: VariantExtensionConfig<VariantT>) -> VariantExtension
    )

    /**
     * Register a new source type to all source sets.
     *
     * The name of the source type will be used to create expected directories names in the various
     * source sets. For instance, src/main/<name> or src/debug/<name>.
     *
     * There is no notion of priorities between the build-type, flavor specific directories, all
     * the expected directories will be interpreted as a flat namespace.
     *
     * Therefore, any [org.gradle.api.Task] that needs access to the entire list of source folders
     * can just use the [Sources.extras]'s [Flat.all] method for that source type.
     *
     * However, If you need to have overriding priorities between the expected directories and
     * therefore require a merging activity, you can still use this API but you will need to
     * create a merging task that will have all sources in input and produce a single output
     * folder for the merged sources.
     *
     * @param name the name of the source type.
     */
    @Incubating
    fun registerSourceType(
        name: String,
    )
}