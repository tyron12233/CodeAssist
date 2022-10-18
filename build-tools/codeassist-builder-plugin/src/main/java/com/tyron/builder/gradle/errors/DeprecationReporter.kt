package com.tyron.builder.gradle.errors

import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.Option
import com.tyron.builder.gradle.options.Version

/**
 * Reporter for issues during evaluation.
 *
 * This handles dealing with errors differently if the project is being run from the command line
 * or from the IDE, in particular during Sync when we don't want to throw any exception
 */
interface DeprecationReporter {

    /** Enum for deprecated element removal target.  */
    enum class DeprecationTarget constructor(

        /**
         * The target version when a deprecated element will be removed.
         *
         * Usage note: Do not use this field to construct a deprecation message, use
         * getDeprecationTargetMessage() instead to ensure consistent message format.
         */
        val removalTarget: Version,

        /**
         * Additional message to be shown below the pre-formatted error/warning message.
         *
         * Note that this additional message should be constructed such that it fits well in the
         * overall message:
         *
         *     "This feature will be removed in version X.Y of the Android Gradle plugin.\n
         *     $additionalMessage"
         *
         * For example, avoid writing additional messages that say "This feature is planned for
         * removal", as it will be duplicated.
         */
        private val additionalMessage: String? = null
    ) {
        VERSION_9_0(Version.VERSION_9_0),

        VERSION_8_0(Version.VERSION_8_0),

        // Obsolete dx Dex Options
        DEX_OPTIONS(Version.VERSION_8_0, "Using it has no effect, and the Android" +
                "Gradle plugin optimizes dexing automatically."),

        // Deprecation of Task Access in the variant API
        TASK_ACCESS_VIA_VARIANT(Version.VERSION_9_0),

        USE_PROPERTIES(
            Version.VERSION_8_0,
            "Gradle Properties must be used to change Variant information."
        ),

        LINT_CHECK_ONLY(
            Version.VERSION_8_0,
            "`check` has been renamed to `checkOnly` to make it clear that it " +
                    "will turn off all other checks than those explicitly listed. If that is " +
                    "what you actually intended, use `checkOnly`; otherwise switch to `enable`."
        ),

        DEFAULT_PUBLISH_CONFIG(
            Version.VERSION_8_0,
            "The support for publishing artifacts with Maven Plugin is removed, " +
                    "please migrate Maven Publish Plugin. See " +
                    "https://developer.android.com/studio/build/maven-publish-plugin for more information."
        ),

        ENABLE_UNCOMPRESSED_NATIVE_LIBS_IN_BUNDLE(
            Version.VERSION_8_0,
            """
                You can add the following to your build.gradle instead:
                android {
                    packagingOptions {
                        jniLibs {
                            useLegacyPackaging = true
                        }
                    }
                }
            """.trimIndent()
        ),
        TRANSFORM_API(
            Version.VERSION_8_0,
            """
                The Transform API is removed to improve build performance. Projects that use the
                Transform API force the Android Gradle plugin to use a less optimized flow for the
                build that can result in large regressions in build times. It’s also difficult to
                use the Transform API and combine it with other Gradle features; the replacement
                APIs aim to make it easier to extend the build without introducing performance or
                correctness issues.

                There is no single replacement for the Transform API—there are new, targeted
                APIs for each use case. All the replacement APIs are in the
                `androidComponents {}` block.

                The Transform API uses incremental APIs deprecated since Gradle 7.5. Please add
                `${BooleanOption.LEGACY_TRANSFORM_TASK_FORCE_NON_INCREMENTAL.propertyName}=true` to
                `gradle.properties` to fix this issue. Note that this will run transforms
                non-incrementally and may have a build performance impact.
            """.trimIndent()
        )
        ;

        fun getDeprecationTargetMessage(): String {
            return removalTarget.getDeprecationTargetMessage() +
                    (additionalMessage?.let { "\n$it" } ?: "")
        }
    }

    /**
     * Reports a deprecation usage in the DSL/API.
     *
     * @param newDslElement the DSL element to use instead, with the name of the class owning it
     * @param oldDslElement the name of the deprecated element, with the name of the class
     * owning it.
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportDeprecatedUsage(
        newDslElement: String,
        oldDslElement: String,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports a deprecation usage in the DSL/API.
     *
     * @param newApiElement the DSL element to use instead, with the name of the class owning it
     * @param oldApiElement the name of the deprecated element, with the name of the class
     * owning it.
     * @param url URL to documentation about the deprecation
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportDeprecatedApi(
        newApiElement: String?,
        oldApiElement: String,
        url: String,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports a deprecated value usage for a DSL element in the DSL/API.
     *
     * @param dslElement name of DSL element containing the deprecated value, with the name of the
     * class.
     * @param oldValue value of the DSL element which has been deprecated.
     * @param newValue optional new value replacing the deprecated value.
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportDeprecatedValue(
        dslElement: String,
        oldValue: String,
        newValue: String?,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports a deprecation usage in the DSL/API.
     *
     * @param oldDslElement the name of the deprecated element, with the name of the class
     * owning it.
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportObsoleteUsage(
        oldDslElement: String,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports a renamed Configuration.
     *
     * @param newConfiguration the name of the [org.gradle.api.artifacts.Configuration] to use
     * instead
     * @param oldConfiguration the name of the deprecated [org.gradle.api.artifacts.Configuration]
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportRenamedConfiguration(
        newConfiguration: String,
        oldConfiguration: String,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports a deprecated Configuration, that gets replaced by an optional DSL element
     *
     * @param newDslElement the name of the DSL element that replaces the configuration
     * @param oldConfiguration the name of the deprecated [org.gradle.api.artifacts.Configuration]
     * @param deprecationTarget when the deprecated element is going to be removed. A line about the
     * timing is added to the message.
     */
    fun reportDeprecatedConfiguration(
        newDslElement: String,
        oldConfiguration: String,
        deprecationTarget: DeprecationTarget)

    /**
     * Reports issues with the given option if there are any.
     *
     * @param option the option to report issues for
     * @param value the value of the option
     */
    fun reportOptionIssuesIfAny(option: Option<*>, value: Any)

}
