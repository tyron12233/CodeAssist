package com.tyron.builder.gradle.internal.services

import com.tyron.builder.gradle.internal.services.VariantBuilderServices.Value

class VariantBuilderServicesImpl(
    projectServices: ProjectServices
): BaseServicesImpl(projectServices),
    VariantBuilderServices {

    override fun lockValues() {
        disallowSet = true
        disallowGet = false
    }

    private var disallowGet: Boolean = true
    private var disallowSet: Boolean = false

    override fun <T> valueOf(value: T): Value<T> = ValueImpl<T>(value)

    override val isPostVariantApi: Boolean
        get() = disallowSet

    inner class ValueImpl<T>(private var value: T): Value<T> {

        override fun set(value: T) {
            if (disallowSet) {
                throw RuntimeException("Values of Variant objects are locked.")
            }
            this.value = value
        }

        override fun get(): T {
            if (disallowGet) {
                throw RuntimeException("Values of Variant objects are not readable. Use VariantProperties instead.")
            }
            return this.value
        }
    }
}

