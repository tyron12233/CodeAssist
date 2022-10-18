package com.tyron.builder.gradle.internal.dsl


import com.tyron.builder.api.dsl.CommonExtension
import org.gradle.api.provider.MapProperty

enum class ModulePropertyKeys(private val keyValue: String, private val defaultValue: Any) {

    /**
     * If false - the test APK instruments the target project APK, and the classes are provided.
     * If true - the test APK targets itself (e.g. for macro benchmarks)
     */
    SELF_INSTRUMENTING("android.experimental.self-instrumenting", false);


    fun getValue(properties: Map<String, Any>): Any {
        return properties[keyValue] ?: return defaultValue
    }

    fun getValueAsBoolean(properties: Map<String, Any>): Boolean {
        val value = properties[keyValue]
        if (value is Boolean) return value
        return if (value is String) value.toBoolean()
        else defaultValue as Boolean
    }
}
