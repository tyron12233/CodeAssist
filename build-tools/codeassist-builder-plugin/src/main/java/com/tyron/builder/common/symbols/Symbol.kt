package com.tyron.builder.common.symbols

import com.android.resources.ResourceType
import com.android.resources.ResourceVisibility
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.tyron.builder.common.resources.ValueResourceNameValidator
import com.tyron.builder.compiler.manifest.MergingException
import javax.annotation.concurrent.Immutable

/**
 * Symbols are used to refer to Android resources.
 *
 * A resource type identifies the group or resource. Resources in Android can have various types:
 * drawables, strings, etc. The full list of supported resource types can be found in
 * `com.android.resources.ResourceType`.
 *
 * The name of the symbol has to be a valid java identifier and is usually the file name of the
 * resource (without the extension) or the name of the resource if the resource is part of an XML
 * file.
 *
 * Names of resources declared in XML files can contain dots and colons, these are replaced by
 * underscores when accessed from java. To sanitize the resource name, call
 * [canonicalizeValueResourceName].
 *
 * For example, the resource `drawable/foo.png` has name `foo`.
 * The string `bar` in file `values/strings.xml` with name `bar` has resource name `bar`.
 *
 * The java type is the java data type that contains the resource value.
 * This is generally `int`, but other values (such as `int[]`) are allowed.
 * Type should not contain any whitespaces, be `null` or empty.
 *
 * The value is a java expression that conforms to the resource type and contains the value of
 * the resource. This may be just an integer like `3`, if the resource has type `int`.
 * But may be a more complex expression. For example, if the resource has type `int[]`, the
 * value may be something such as `{1, 2, 3}`.
 *
 * In practice, symbols do not exist by themselves. They are usually part of a symbol table, but
 * this class is independent of any use.
 */
@Immutable
sealed class Symbol {

    abstract val resourceVisibility: ResourceVisibility
    abstract val resourceType: ResourceType
    abstract val canonicalName:String
    abstract val name: String
    /** The value as a string. */
    abstract fun getValue():String
    abstract val intValue: Int
    abstract val javaType: SymbolJavaType
    abstract val children: ImmutableList<String>

    companion object {

        @JvmField val NO_CHILDREN: ImmutableList<String> = ImmutableList.of()


        /**
         * Creates a new non-stylable symbol.
         *
         * The `cannonicalName` of the symbol will be the sanitized resource
         * name (See [canonicalizeValueResourceName].)
         * @param resourceType the resource type of the symbol
         * @param name the sanitized name of the symbol
         * @param idProvider the provider for the value of the symbol
         * @param validation the `name` of the symbol is a valid resource name.
         */
        @JvmStatic @JvmOverloads fun createSymbol(
            resourceType: ResourceType,
            name: String,
            idProvider: IdProvider,
            isMaybeDefinition: Boolean = false,
            validation: Boolean = true): Symbol {
            return createSymbol(
                resourceType, name, idProvider.next(resourceType), isMaybeDefinition, validation)
        }

        /**
         * Creates a new non-stylable symbol.
         *
         * Validates that the `name` of the symbol is a valid resource name.
         *
         * The `cannonicalName` of the symbol will be the sanitized resource
         * name (See [canonicalizeValueResourceName].)
         * @param resourceType the resource type of the symbol
         * @param name the sanitized name of the symbol
         * @param value the value of the symbol
         */
        @JvmStatic @JvmOverloads fun createSymbol(
            resourceType: ResourceType,
            name: String,
            value: Int,
            isMaybeDefinition: Boolean = false,
            validation: Boolean = true): Symbol {
            if (validation) {
                validateSymbol(name, resourceType)
            }
            return if (resourceType == ResourceType.ATTR) {
                attributeSymbol(
                    name = name,
                    intValue = value,
                    isMaybeDefinition = isMaybeDefinition)
            } else {
                normalSymbol(
                    resourceType = resourceType,
                    name = name,
                    intValue = value
                )
            }
        }

        /**
         * Creates a new styleable symbol.
         *
         * The `cannonicalName` of the symbol will be the sanitized resource
         * name (See [canonicalizeValueResourceName].)
         *
         * For example:
         * ```
         * int[] styleable S1 {0x7f040001,0x7f040002}
         * int styleable S1_attr1 0
         * int styleable S1_attr2 1
         * ```
         *  corresponds to a StylableSymbol with value
         *  `[0x7f040001,0x7f040002]` and children `["attr1", "attr2"]`.
         *
         * @param name the sanitized name of the symbol
         * @param validation check the `name` of the symbol is a valid resource name.
         */
        @JvmStatic fun createStyleableSymbol(
            name: String,
            values: ImmutableList<Int>,
            children: List<String> = emptyList(),
            validation: Boolean = true): StyleableSymbol {
            if (validation) {
                validateSymbol(name, ResourceType.STYLEABLE)
            }
            return styleableSymbol(
                name = name,
                canonicalName = canonicalizeValueResourceName(name),
                values = values,
                children = ImmutableList.copyOf(children)
            )
        }

        /**
         * Checks whether the given resource name meets all the criteria: cannot be null or empty,
         * cannot contain whitespaces or dots. Also checks if the given resource type is a valid.
         *
         * @param name the name of the resource that needs to be validated
         */
        private fun validateSymbol(name: String, resourceType: ResourceType) {
            try {
                ValueResourceNameValidator.validate(name, resourceType, null)
            } catch (e: MergingException) {
                throw IllegalArgumentException(
                    "Validation of a resource with name '$name' and type " +
                            "'${resourceType.getName()}' failed.'",
                    e)
            }

        }

        @JvmStatic
        @JvmOverloads
        fun normalSymbol(
            resourceType: ResourceType,
            name: String,
            intValue: Int = 0,
            resourceVisibility: ResourceVisibility = ResourceVisibility.UNDEFINED,
            canonicalName: String = canonicalizeValueResourceName(name)
        ) : NormalSymbol {
            Preconditions.checkArgument(resourceType != ResourceType.STYLEABLE,
                "Internal Error: Styleables must be represented by StyleableSymbol.")
            Preconditions.checkArgument(resourceType != ResourceType.ATTR,
                "Internal Error: Attributes must be represented by AttributeSymbol.")
            if (intValue == 0 && resourceVisibility == ResourceVisibility.UNDEFINED && canonicalName == name) {
                return BasicNormalSymbol(resourceType, name)
            }
            return NormalSymbolImpl(resourceType, name, intValue, resourceVisibility, canonicalName)
        }

        @JvmStatic
        @JvmOverloads
        fun attributeSymbol(
            name: String,
            intValue: Int = 0,
            isMaybeDefinition: Boolean = false,
            resourceVisibility: ResourceVisibility = ResourceVisibility.UNDEFINED,
            canonicalName: String = canonicalizeValueResourceName(name)
        ): AttributeSymbol {
            if (intValue == 0 && isMaybeDefinition == false && resourceVisibility == ResourceVisibility.UNDEFINED && canonicalName == name) {
                return BasicAttributeSymbol(name)
            }
            return AttributeSymbolImpl(name, intValue, isMaybeDefinition, resourceVisibility, canonicalName)
        }

        @JvmStatic
        @JvmOverloads
        fun styleableSymbol(
            name: String,
            values: ImmutableList<Int> = ImmutableList.of(),
            children: ImmutableList<String>,
            resourceVisibility: ResourceVisibility = ResourceVisibility.UNDEFINED,
            canonicalName: String = canonicalizeValueResourceName(name)
        ): StyleableSymbol {
            if (values.isEmpty() && resourceVisibility == ResourceVisibility.UNDEFINED && canonicalName == name) {
                return BasicStyleableSymbol(name, children)
            }
            return StyleableSymbolImpl(name, values, children, resourceVisibility, canonicalName)
        }
    }

    abstract class NormalSymbol internal constructor(): Symbol() {
        final override fun toString(): String =
            "$resourceVisibility $resourceType $canonicalName = 0x${intValue.toString(16)}"
        final override val javaType: SymbolJavaType
            get() = SymbolJavaType.INT
        final override fun getValue(): String = "0x${Integer.toHexString(intValue)}"
        final override val children: ImmutableList<String>
            get() = throw UnsupportedOperationException("Only styleables have children.")
    }

    /**
     * A normal symbol which can't be represented by [BasicNormalSymbol].
     *
     * The use of the data class generated equals method means that it is vital that
     * this class is never used to represent a symbol that could be represented by
     * [BasicNormalSymbol]
     */
    internal data class NormalSymbolImpl(
        override val resourceType: ResourceType,
        override val name: String,
        override val intValue: Int,
        override val resourceVisibility: ResourceVisibility,
        override val canonicalName: String
    ) : NormalSymbol()

    /** A normal symbol with only type and name, e.g. loaded from a symbol list with package name. */
    internal data class BasicNormalSymbol(
        override val resourceType: ResourceType,
        override val name: String
    ) : NormalSymbol() {
        override val intValue: Int
            get() = 0
        override val resourceVisibility: ResourceVisibility
            get() = ResourceVisibility.UNDEFINED
        override val canonicalName: String
            get() = name
    }

    /**
     * TODO: add attribute format
     */
    abstract class AttributeSymbol : Symbol() {
        abstract val isMaybeDefinition: Boolean
        final override val resourceType: ResourceType
            get() = ResourceType.ATTR
        final override val javaType: SymbolJavaType
            get() = SymbolJavaType.INT

        final override fun getValue(): String = "0x${Integer.toHexString(intValue)}"
        final override val children: ImmutableList<String>
            get() = throw UnsupportedOperationException("Attributes cannot have children.")

        final override fun toString(): String {
            val maybeSuffix = if (isMaybeDefinition) "?" else ""
            return "$resourceVisibility $resourceType$maybeSuffix $canonicalName = 0x${intValue.toString(16)}"
        }
    }

    /**
     * An attribute symbol which can't be represented by [BasicAttributeSymbol].
     *
     * The use of the data class generated equals method means that it is vital that
     * this class is never used to represent a symbol that could be represented by
     * [BasicAttributeSymbol]
     */
    internal data class AttributeSymbolImpl(
        override val name: String,
        override val intValue: Int,
        override val isMaybeDefinition: Boolean = false,
        override val resourceVisibility: ResourceVisibility = ResourceVisibility.UNDEFINED,
        override val canonicalName: String = canonicalizeValueResourceName(name)
    ) : AttributeSymbol()

    /** An attribute symbol with only name, e.g. loaded from a symbol list with package name. */
    internal data class BasicAttributeSymbol(
        override val name: String
    ) : AttributeSymbol() {
        override val intValue: Int
            get() = 0
        override val isMaybeDefinition
            get() = false
        override val resourceVisibility: ResourceVisibility
            get() = ResourceVisibility.UNDEFINED
        override val canonicalName: String
            get() = name
    }

    abstract class StyleableSymbol: Symbol() {
        final override val resourceType: ResourceType
            get() = ResourceType.STYLEABLE
        final override val intValue: Int
            get() = throw UnsupportedOperationException("Styleables have no int value")
        abstract val values: ImmutableList<Int>

        final override fun getValue(): String =
            StringBuilder(values.size * 12 + 2).apply {
                append("{ ")
                for (i in 0 until values.size) {
                    if (i != 0) { append(", ") }
                    append("0x")
                    append(Integer.toHexString(values[i]))
                }
                append(" }")
            }.toString()

        final override val javaType: SymbolJavaType
            get() = SymbolJavaType.INT_LIST
    }

    /**
     * An styleable symbol which can't be represented by [BasicStyleableSymbol].
     *
     * The use of the data class generated equals method means that it is vital that
     * this class is never used to represent a symbol that could be represented by
     * [BasicStyleableSymbol]
     */
    internal data class StyleableSymbolImpl constructor(
        override val name: String,
        override val values: ImmutableList<Int>,
        /**
         * list of the symbol's children in order corresponding to their IDs in the value list.
         * For example:
         * ```
         * int[] styleable S1 {0x7f040001,0x7f040002}
         * int styleable S1_attr1 0
         * int styleable S1_attr2 1
         * ```
         *  corresponds to a Symbol with value `"{0x7f040001,0x7f040002}"` and children `{"attr1",
         * "attr2"}`.
         */
        override val children: ImmutableList<String>,
        override val resourceVisibility: ResourceVisibility = ResourceVisibility.UNDEFINED,
        override val canonicalName: String = canonicalizeValueResourceName(name)
    ) : StyleableSymbol()

    /** An styleable symbol with only name and children e.g. loaded from a symbol list with package name. */
    internal data class BasicStyleableSymbol constructor(
        override val name: String,
        override val children: ImmutableList<String>
    ) : StyleableSymbol() {
        override val values: ImmutableList<Int>
            get() = ImmutableList.of()
        override val resourceVisibility: ResourceVisibility
            get() = ResourceVisibility.UNDEFINED
        override val canonicalName: String
            get() = name
    }
}