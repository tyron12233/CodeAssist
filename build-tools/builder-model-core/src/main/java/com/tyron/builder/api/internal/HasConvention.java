package com.tyron.builder.api.internal;

import com.tyron.builder.api.plugins.Convention;
import com.tyron.builder.api.plugins.ExtensionAware;

/**
 * Demarcates objects that expose a convention.
 *
 * Convention objects aren't going to be around forever, so this is a temporary interface.
 *
 * @deprecated Use extensions instead. This interface is scheduled for removal in Gradle 8.
 * @see ExtensionAware
 */
@Deprecated
public interface HasConvention {
    @Deprecated
    Convention getConvention();
}