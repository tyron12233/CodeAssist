package com.tyron.kotlin_completion.index

import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.name.FqName

data class Symbol(
    val fqName: FqName,
    val kind: Kind,
    val visibility: Visibility,
    val extensionReceiverType: FqName?
) {

    enum class Kind(val rawValue: Int) {
        CLASS(0),
        INTERFACE(1),
        FUNCTION(2),
        VARIABLE(3),
        MODULE(4),
        ENUM(5),
        ENUM_MEMBER(6),
        CONSTRUCTOR(7),
        FIELD(8),
        UNKNOWN(9);

        companion object {
            fun fromRaw(rawValue: Int) = Kind.values().firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
        }
    }
    enum class Visibility(val rawValue: Int) {
        PRIVATE_TO_THIS(0),
        PRIVATE(1),
        INTERNAL(2),
        PROTECTED(3),
        PUBLIC(4),
        UNKNOWN(5);

        companion object {
            fun fromRaw(rawValue: Int) = values().firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
        }
    }
}