package com.tyron.builder.gradle.options

import com.tyron.builder.gradle.errors.DeprecationReporter

interface Option<out T> {

    sealed class Status {

        object EXPERIMENTAL : Status()

        object STABLE : Status()

        class Deprecated(val deprecationTarget: DeprecationReporter.DeprecationTarget) :
            Status() {

            fun getDeprecationTargetMessage(): String {
                return deprecationTarget.getDeprecationTargetMessage()
            }
        }

        class Removed(

            /**
             * The version when an element was removed.
             *
             * Usage note: Do not use this field to construct a removal message, use
             * getRemovedVersionMessage() instead to ensure consistent message format.
             */
            val removedVersion: Version,

            /**
             * Additional message to be shown below the pre-formatted error/warning message.
             *
             * Note that this additional message should be constructed such that it fits well in the
             * overall message:
             *
             *     "This feature was removed in version X.Y of the Android Gradle plugin.\n
             *     $additionalMessage"
             *
             * For example, avoid writing additional messages that say "This feature has been
             * removed", as it will be duplicated.
             */
            private val additionalMessage: String? = null

        ) : Status() {

            fun getRemovedVersionMessage(): String {
                return removedVersion.getRemovedVersionMessage() +
                        (additionalMessage?.let { "\n$it" } ?: "")
            }
        }
    }

    val propertyName: String

    val defaultValue: T?
        get() = null

    val status: Status

    fun parse(value: Any): T
}