package com.tyron.builder.api.variant

import java.io.Serializable

/**
 * Field definition for the generated BuildConfig class.
 *
 * The field is generated as: <type> <name> = <value>;
 */
class BuildConfigField<T: Serializable>(
    /**
     * Value to be written as BuildConfig field type.
     */
    val type: String,

    /**
     * Value of the generated field.
     * If [type] is [String], then [value] should include quotes.
     */
    val value: T,

    /**
     * Optional field comment that will be added to the generated source file or null if no comment
     * is necessary.
     */
    val comment: String?
) : Serializable