package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.gradle.internal.variant.DimensionCombination
import com.tyron.builder.core.ComponentType
import com.android.utils.appendCapitalized
import com.android.utils.combineAsCamelCase

internal const val DEFAULT_TEST_RUNNER = "android.test.InstrumentationTestRunner"
internal const val MULTIDEX_TEST_RUNNER =
    "com.android.test.runner.MultiDexTestRunner"
internal const val DEFAULT_HANDLE_PROFILING = false
internal const val DEFAULT_FUNCTIONAL_TEST = false

/**
 * Returns the full, unique name of the variant in camel case (starting with a lower case),
 * including BuildType, Flavors and Test (if applicable).
 *
 * This is to be used for the normal variant name. In case of Feature plugin, the library
 * side will be called the same as for library plugins, while the feature side will add
 * 'feature' to the name.
 *
 * Also computes the flavor name if applicable
 */
@JvmOverloads
fun computeName(
    dimensionCombination: DimensionCombination,
    componentType: ComponentType,
    flavorNameCallback: ((String) -> Unit)? = null
): String {
    // compute the flavor name
    val flavorName = if (dimensionCombination.productFlavors.isEmpty()) {
        ""
    } else {
        combineAsCamelCase(dimensionCombination.productFlavors, Pair<String,String>::second)
    }
    flavorNameCallback?.let { it(flavorName) }

    val sb = StringBuilder()
    val buildType = dimensionCombination.buildType
    if (buildType == null) {
        if (flavorName.isNotEmpty()) {
            sb.append(flavorName)
        } else if (!componentType.isTestComponent && !componentType.isTestFixturesComponent) {
            sb.append("main")
        }
    } else {
        if (flavorName.isNotEmpty()) {
            sb.append(flavorName)
            sb.appendCapitalized(buildType)
        } else {
            sb.append(buildType)
        }
    }
    if (componentType.isNestedComponent) {
        if (sb.isEmpty()) {
            // need the lower case version
            sb.append(componentType.prefix)
        } else {
            sb.append(componentType.suffix)
        }
    }
    return sb.toString()
}

/**
 * Turns a string into a valid source set name for the given [ComponentType], e.g.
 * "fooBarUnitTest" becomes "testFooBar".
 */
fun computeSourceSetName(
    baseName: String,
    componentType: ComponentType
): String {
    var name = baseName
    if (name.endsWith(componentType.suffix)) {
        name = name.substring(0, name.length - componentType.suffix.length)
    }
    if (componentType.prefix.isNotEmpty()) {
        name = componentType.prefix.appendCapitalized(name)
    }
    return name
}