package com.tyron.builder.gradle.internal.dsl;

import com.google.common.collect.ListMultimap;
import java.util.List;

/**
 * Options for configuring scoped shader options.
 */
public interface CoreShaderOptions {

    /**
     * Returns the list of glslc args.
     */
    List<String> getGlslcArgs();

    /**
     * Returns the list of scoped glsl args.
     */
    ListMultimap<String, String> getScopedGlslcArgs();
}