package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Options for view binding
 *
 * @since 4.2
 */
interface ViewBindingOptions: AndroidModel {
    /** Whether to enable view binding.  */
    val isEnabled: Boolean
}