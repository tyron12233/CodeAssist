package com.tyron.builder.api.component.impl

import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig

/**
 * This class and subclasses are implementing methods defined in the CreationConfig
 * interfaces but should not be necessarily implemented by the VariantImpl
 * and subclasses. The reasons are usually because it makes more sense to implement
 * the method in a class hierarchy that follows the interface definition so to avoid
 * repeating implementation in various disparate VariantImpl sub-classes.
 *
 * Instead [com.android.build.api.variant.impl.VariantImpl] will delegate
 * to these objects for methods which are cross cutting across the Variant
 * implementation hierarchy.
 */

/**
 * Constructor for [ConsumableCreationConfigImpl].
 *
 * @param config configuration object that will be delegating calls to this
 * object, and will also provide access to other Variant configuration data
 * @param dslInfo variant configuration coming from the DSL.
 */
open class ConsumableCreationConfigImpl<T: ConsumableCreationConfig>(
    protected val config: T,
) {
}