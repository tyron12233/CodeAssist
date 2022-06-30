package com.tyron.builder.api.internal.tasks.compile;


import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.compile.AbstractOptions;

import org.jetbrains.annotations.Nullable;

/**
 * Debug options for Java compilation. Only take effect if {@link CompileOptions#debug}
 * is set to {@code true}.
 */
public class DebugOptions extends AbstractOptions {
    private static final long serialVersionUID = 0;

    private String debugLevel;

    /**
     * Tells which debugging information is to be generated. The value is a comma-separated
     * list of any of the following keywords (without spaces in between):
     *
     * <dl>
     *     <dt>{@code source}
     *     <dd>Source file debugging information
     *     <dt>{@code lines}
     *     <dd>Line number debugging information
     *     <dt>{@code vars}
     *     <dd>Local variable debugging information
     * </dl>
     *
     * By default, only source and line debugging information will be generated.
     */
    @Nullable
    @Optional
    @Input
    public String getDebugLevel() {
        return debugLevel;
    }

    /**
     * Sets which debug information is to be generated.
     */
    public void setDebugLevel(@Nullable String debugLevel) {
        this.debugLevel = debugLevel;
    }
}