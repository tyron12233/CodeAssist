package com.tyron.builder.api.dsl

/**
 * DSL object for configuring databinding options.
 */
interface DataBinding {
    /** The version of data binding to use. */
    var version: String?

    /** Whether to add the default data binding adapters. */
    var addDefaultAdapters: Boolean

    /**
     * Whether to add the data binding KTX features.
     * A null value means that the user hasn't specified any value in the DSL.
     * The default value can be tweaked globally using the
     * `android.defaults.databinding.addKtx` gradle property.
     */
    var addKtx: Boolean?

    @Deprecated("deprecated, use enableForTests", ReplaceWith("enableForTests"))
    var isEnabledForTests: Boolean

    /** Whether to run data binding code generation for test projects. */
    var enableForTests: Boolean

    @Deprecated("deprecated, use enable", ReplaceWith("enable"))
    var isEnabled: Boolean

    /** Whether to enable data binding. */
    var enable: Boolean
}
