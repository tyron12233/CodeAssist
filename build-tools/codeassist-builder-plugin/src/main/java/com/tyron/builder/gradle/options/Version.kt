package com.tyron.builder.gradle.options

/** An Android Gradle plugin version. */
enum class Version(

    /**
     * String value of the version, or `null` if it is not known (e.g., [VERSION_BEFORE_4_0]).
     *
     * Usage note: Do not use this field to construct a deprecation/removal message, use
     * getDeprecationTargetMessage()/getRemovedVersionMessage() instead to ensure consistent message
     * format.
     */
     val versionString: String?
) {

    /**
     * A version before version 4.0, used when the exact version is not known, except that it's
     * guaranteed to be before 4.0.
     */
    VERSION_BEFORE_4_0(null),

    VERSION_3_5("3.5"),
    VERSION_3_6("3.6"),
    VERSION_4_0("4.0"),
    VERSION_4_1("4.1"),
    VERSION_4_2("4.2"),
    VERSION_7_0("7.0"),
    VERSION_7_2("7.2"),
    VERSION_7_3("7.3"),
    VERSION_8_0("8.0"),
    VERSION_9_0("9.0"),

    ; // end of enums

    fun getDeprecationTargetMessage(): String {
        check(this != VERSION_BEFORE_4_0)
        return "It will be removed in version $versionString of the Android Gradle plugin."
    }

    fun getRemovedVersionMessage(): String {
        return if (this == VERSION_BEFORE_4_0) {
            "It has been removed from the current version of the Android Gradle plugin."
        } else {
            "It was removed in version $versionString of the Android Gradle plugin."
        }
    }
}