package org.gradle.api.services;

import org.gradle.api.Incubating;

/**
 * A set of parameters to be injected into a {@link BuildService} implementation.
 *
 * @since 6.1
 */
@Incubating
public interface BuildServiceParameters {
    /**
     * Used for services without parameters.
     *
     * @since 6.1
     */
    @Incubating
    final class None implements BuildServiceParameters {
        private None() {
        }
    }
}
