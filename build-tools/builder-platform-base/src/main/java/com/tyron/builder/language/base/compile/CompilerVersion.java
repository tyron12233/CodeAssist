package com.tyron.builder.language.base.compile;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.tasks.Input;

/**
 * Version of a compiler.
 *
 * @since 4.4
 */
@Incubating
//@NonNullApi
public interface CompilerVersion {

    /**
     * Returns the type of the compiler.
     */
    @Input
    String getType();

    /**
     * Returns the vendor of the compiler.
     */
    @Input
    String getVendor();

    /**
     * Returns the version of the compiler.
     */
    @Input
    String getVersion();
}
