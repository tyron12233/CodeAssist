package com.tyron.builder.gradle.internal.dsl;

import com.tyron.builder.core.DefaultDexOptions;

import java.util.Arrays;

/**
 * DSL object for configuring dx options.
 *
 * @deprecated AGP does not use these options for dexing any more.
 */
@Deprecated
@SuppressWarnings("unused") // Exposed in the DSL.
public class DexOptions extends DefaultDexOptions {

    /** @deprecated ignored */
    @Deprecated
    public boolean getIncremental() {
        return false;
    }

    public void setIncremental(boolean ignored) {
    }

    public void additionalParameters(String... parameters) {
        this.setAdditionalParameters(Arrays.asList(parameters));
    }

    /**
     * @deprecated Dex will always be optimized. Invoking this method has no effect.
     */
    @Deprecated
    public void setOptimize(@SuppressWarnings("UnusedParameters") Boolean optimize) {
    }
}