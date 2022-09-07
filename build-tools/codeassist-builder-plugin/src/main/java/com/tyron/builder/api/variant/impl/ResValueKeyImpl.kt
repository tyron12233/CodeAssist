package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.ResValue

data class ResValueKeyImpl(
    override val type: String,
    override val name: String
): ResValue.Key {

    /**
     * As [com.tyron.builder.model.BaseConfig.resValues] map has a string key, this method is used
     * to convert the resValue key into a string representation to be used in the model to avoid
     * changing the method signature.
     */
    override fun toString(): String {
        return "$type/$name"
    }
}
