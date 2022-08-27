package com.tyron.builder.gradle.internal.dependency

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements

/** Contains attributes of different types. */
class AndroidAttributes @JvmOverloads constructor(
    val stringAttributes: Map<Attribute<String>, String>? = null,
    val libraryElementsAttribute: LibraryElements? = null,
    val category: Category? = null,
) {

    constructor(stringAttribute: Pair<Attribute<String>, String>) : this(mapOf(stringAttribute))

    fun addAttributesToContainer(container: AttributeContainer) {
        stringAttributes?.let {
            for ((key, value) in it) {
                container.attribute(key, value)
            }
        }
        libraryElementsAttribute?.let {
            container.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, it)
        }
        category?.let {
            container.attribute(Category.CATEGORY_ATTRIBUTE, it)
        }
    }

    /** Returns a string listing all the attributes. */
    fun toAttributeMapString(): String {
        val stringAttrs = stringAttributes?.let { stringAttributes ->
            stringAttributes.entries
                .sortedBy { it.key.name }
                .fold("") { it, entry -> "$it-A${entry.key.name}=${entry.value}" }
        } ?: ""
        val libraryElementsAttr = libraryElementsAttribute?.let {
            "-A${LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name}=$it"
        } ?: ""
        val categoryAttr = category?.let { "-A${Category.CATEGORY_ATTRIBUTE.name}=$it" } ?: ""
        return stringAttrs + libraryElementsAttr + categoryAttr
    }

    override fun toString() = toAttributeMapString()
}