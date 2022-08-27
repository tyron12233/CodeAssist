@file:JvmName("OptionParsers")

package com.tyron.builder.gradle.errors

import java.util.Locale

fun parseBoolean(propertyName: String, value: Any): Boolean {
    return when (value) {
        is Boolean -> value
        is CharSequence ->
            when (value.toString().lowercase(Locale.US)) {
                "true" -> true
                "false" -> false
                else -> parseBooleanFailure(propertyName, value)
            }
        is Number ->
            when (value.toInt()) {
                0 -> false
                1 -> true
                else -> parseBooleanFailure(propertyName, value)
            }
        else -> parseBooleanFailure(propertyName, value)
    }
}

private fun parseBooleanFailure(propertyName: String, value: Any): Nothing {
    throw IllegalArgumentException(
        "Cannot parse project property "
                + propertyName
                + "='"
                + value
                + "' of type '"
                + value.javaClass
                + "' as boolean. Expected 'true' or 'false'."
    )
}