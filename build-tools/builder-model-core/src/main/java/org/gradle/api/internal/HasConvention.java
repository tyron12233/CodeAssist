package org.gradle.api.internal;

import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.ExtensionAware;

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