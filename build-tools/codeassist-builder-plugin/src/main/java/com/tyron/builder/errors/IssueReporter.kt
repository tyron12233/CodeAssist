package com.tyron.builder.errors

import com.tyron.builder.model.SyncIssue

/**
 * Base class for error reporting.
 *
 * This allows returning configuration/evaluation error to an IDE, by running in a lenient
 * mode and only records but does not throw exception.
 * [IssueReporter.hasIssue] allows checking if a given issue has already been recorded.
 *
 * When using inside a build step, use [DefaultIssueReporter] which will always throw on error.
 */
abstract class IssueReporter {

    enum class Severity constructor(val severity: Int) {
        WARNING(SyncIssue.SEVERITY_WARNING),
        ERROR(SyncIssue.SEVERITY_ERROR),
    }

    @Suppress("DEPRECATION")
    enum class Type constructor(val type: Int) {
        GENERIC(SyncIssue.TYPE_GENERIC),
        PLUGIN_OBSOLETE(SyncIssue.TYPE_PLUGIN_OBSOLETE),
        UNRESOLVED_DEPENDENCY(SyncIssue.TYPE_UNRESOLVED_DEPENDENCY),
        DEPENDENCY_IS_APK(SyncIssue.TYPE_DEPENDENCY_IS_APK),
        DEPENDENCY_IS_APKLIB(SyncIssue.TYPE_DEPENDENCY_IS_APKLIB),
        NON_JAR_LOCAL_DEP(SyncIssue.TYPE_NON_JAR_LOCAL_DEP),
        NON_JAR_PACKAGE_DEP(SyncIssue.TYPE_NON_JAR_PACKAGE_DEP),
        NON_JAR_PROVIDED_DEP(SyncIssue.TYPE_NON_JAR_PROVIDED_DEP),
        JAR_DEPEND_ON_AAR(SyncIssue.TYPE_JAR_DEPEND_ON_AAR),
        MISMATCH_DEP(SyncIssue.TYPE_MISMATCH_DEP),
        OPTIONAL_LIB_NOT_FOUND(SyncIssue.TYPE_OPTIONAL_LIB_NOT_FOUND),
        JACK_IS_NOT_SUPPORTED(SyncIssue.TYPE_JACK_IS_NOT_SUPPORTED),
        GRADLE_TOO_OLD(SyncIssue.TYPE_GRADLE_TOO_OLD),
        BUILD_TOOLS_TOO_LOW(SyncIssue.TYPE_BUILD_TOOLS_TOO_LOW),
        DEPENDENCY_MAVEN_ANDROID(SyncIssue.TYPE_DEPENDENCY_MAVEN_ANDROID),
        DEPENDENCY_INTERNAL_CONFLICT(SyncIssue.TYPE_DEPENDENCY_INTERNAL_CONFLICT),
        EXTERNAL_NATIVE_BUILD_CONFIGURATION(SyncIssue.TYPE_EXTERNAL_NATIVE_BUILD_CONFIGURATION),
        EXTERNAL_NATIVE_BUILD_PROCESS_EXCEPTION(SyncIssue.TYPE_EXTERNAL_NATIVE_BUILD_PROCESS_EXCEPTION),
        JACK_REQUIRED_FOR_JAVA_8_LANGUAGE_FEATURES(SyncIssue.TYPE_JACK_REQUIRED_FOR_JAVA_8_LANGUAGE_FEATURES),
        DEPENDENCY_WEAR_APK_TOO_MANY(SyncIssue.TYPE_DEPENDENCY_WEAR_APK_TOO_MANY),
        DEPENDENCY_WEAR_APK_WITH_UNBUNDLED(SyncIssue.TYPE_DEPENDENCY_WEAR_APK_WITH_UNBUNDLED),
        JAR_DEPEND_ON_ATOM(SyncIssue.TYPE_JAR_DEPEND_ON_ATOM),
        AAR_DEPEND_ON_ATOM(SyncIssue.TYPE_AAR_DEPEND_ON_ATOM),
        ATOM_DEPENDENCY_PROVIDED(SyncIssue.TYPE_ATOM_DEPENDENCY_PROVIDED),
        MISSING_SDK_PACKAGE(SyncIssue.TYPE_MISSING_SDK_PACKAGE),
        STUDIO_TOO_OLD(SyncIssue.TYPE_STUDIO_TOO_OLD),
        UNNAMED_FLAVOR_DIMENSION(SyncIssue.TYPE_UNNAMED_FLAVOR_DIMENSION),
        INCOMPATIBLE_PLUGIN(SyncIssue.TYPE_INCOMPATIBLE_PLUGIN),
        DEPRECATED_DSL(SyncIssue.TYPE_DEPRECATED_DSL),
        MIN_SDK_VERSION_IN_MANIFEST(SyncIssue.TYPE_MIN_SDK_VERSION_IN_MANIFEST),
        TARGET_SDK_VERSION_IN_MANIFEST(SyncIssue.TYPE_TARGET_SDK_VERSION_IN_MANIFEST),
        UNSUPPORTED_PROJECT_OPTION_USE(SyncIssue.TYPE_UNSUPPORTED_PROJECT_OPTION_USE),
        MANIFEST_PARSED_DURING_CONFIGURATION(SyncIssue.TYPE_MANIFEST_PARSED_DURING_CONFIGURATION),
        THIRD_PARTY_GRADLE_PLUGIN_TOO_OLD(SyncIssue.TYPE_THIRD_PARTY_GRADLE_PLUGIN_TOO_OLD),
        SIGNING_CONFIG_DECLARED_IN_DYNAMIC_FEATURE(SyncIssue.TYPE_SIGNING_CONFIG_DECLARED_IN_DYNAMIC_FEATURE),
        SDK_NOT_SET(SyncIssue.TYPE_SDK_NOT_SET),
        AMBIGUOUS_BUILD_TYPE_DEFAULT(SyncIssue.TYPE_AMBIGUOUS_BUILD_TYPE_DEFAULT),
        AMBIGUOUS_PRODUCT_FLAVOR_DEFAULT(SyncIssue.TYPE_AMBIGUOUS_PRODUCT_FLAVOR_DEFAULT),
        COMPILE_SDK_VERSION_NOT_SET(SyncIssue.TYPE_COMPILE_SDK_VERSION_NOT_SET),
        ANDROID_X_PROPERTY_NOT_ENABLED(SyncIssue.TYPE_ANDROID_X_PROPERTY_NOT_ENABLED),
        USING_DEPRECATED_CONFIGURATION(SyncIssue.TYPE_USING_DEPRECATED_CONFIGURATION),
        USING_DEPRECATED_DSL_VALUE(SyncIssue.TYPE_USING_DEPRECATED_DSL_VALUE),
        EDIT_LOCKED_DSL_VALUE(SyncIssue.TYPE_EDIT_LOCKED_DSL_VALUE),
        MISSING_ANDROID_MANIFEST(SyncIssue.TYPE_MISSING_ANDROID_MANIFEST),
        JCENTER_IS_DEPRECATED(SyncIssue.TYPE_JCENTER_IS_DEPRECATED),
        AGP_USED_JAVA_VERSION_TOO_LOW(SyncIssue.TYPE_AGP_USED_JAVA_VERSION_TOO_LOW),
        COMPILE_SDK_VERSION_TOO_HIGH(SyncIssue.TYPE_COMPILE_SDK_VERSION_TOO_HIGH),
        COMPILE_SDK_VERSION_TOO_LOW(SyncIssue.TYPE_COMPILE_SDK_VERSION_TOO_LOW),
        ACCESSING_DISABLED_FEATURE_VARIANT_API(SyncIssue.TYPE_ACCESSING_DISABLED_FEATURE_VARIANT_API),
        APPLICATION_ID_MUST_NOT_BE_DYNAMIC(SyncIssue.TYPE_APPLICATION_ID_MUST_NOT_BE_DYNAMIC)
    }

    protected abstract fun reportIssue(type: Type, severity: Severity, exception: EvalIssueException)

    /**
     * Reports an error.
     *
     * When running outside of IDE sync, this will throw an exception and abort execution.
     *
     * @param type the type of the error.
     * @param msg a human readable error (for command line output, or if an older IDE doesn't know
     * this particular issue type.)
     * @param data a data representing the source of the error. This goes hand in hand with the
     * <var>type</var>, and is not meant to be readable. Instead a (possibly translated) message
     * is created from this data and type.
     * @param multilineMsg a human readable error that spans through multiple lines (might require
     * special IDE treatment.)
     */
    @JvmOverloads
    fun reportError(type: Type, msg: String, data: String? = null, multilineMsg: List<String>? = null) {
        reportIssue(
            type,
            Severity.ERROR,
            EvalIssueException(msg, data, multilineMsg))
    }

    /**
     * Reports an error.
     *
     * When running outside of IDE sync, this will throw an exception and abort execution.
     *
     * @param type the type of the error.
     * @param cause exception containing all relevant information about the error.
     */
    fun reportError(type: Type, cause: Exception) {
        reportIssue(
            type,
            Severity.ERROR,
            EvalIssueException(cause))
    }

    /**
     * Reports a warning.
     *
     * Behaves similar to [reportError] but does not abort the build.
     *
     * @param type the type of the error.
     * @param msg a human readable error (for command line output, or if an older IDE doesn't know
     * this particular issue type.)
     * @param data a data representing the source of the error. This goes hand in hand with the
     * <var>type</var>, and is not meant to be readable. Instead a (possibly translated) message
     * is created from this data and type.
     * @param multilineMsg a human readable error that spans through multiple lines (might require
     * special IDE treatment.)
     */
    @JvmOverloads
    fun reportWarning(type: Type, msg: String, data: String? = null, multilineMsg: List<String>? = null) {
        reportIssue(
            type,
            Severity.WARNING,
            EvalIssueException(msg, data, multilineMsg))
    }

    /**
     * Reports a warning.
     *
     * Behaves similar to [reportError] but does not abort the build.
     *
     * @param type the type of the error.
     * @param cause exception containing all relevant information about the error.
     */
    fun reportWarning(type: Type, cause: Exception) {
        reportIssue(
            type,
            Severity.WARNING,
            EvalIssueException(cause))
    }

    /**
     * Whether an issue of the given type has been recorded.
     */
    abstract fun hasIssue(type: Type): Boolean

}