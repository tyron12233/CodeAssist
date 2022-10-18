package com.tyron.builder.model.v2.dsl

import com.tyron.builder.model.v2.AndroidModel

/**
 * A Simple class field with name, type and value, all as strings.
 *
 * @since 4.2
 */
interface ClassField: AndroidModel {
    val type: String
    val name: String
    val value: String
    val documentation: String
    val annotations: Set<String>
}
