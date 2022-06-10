package org.gradle.api.component;

import org.gradle.api.Named;
import org.gradle.internal.HasInternalProtocol;

/**
 * A software component produced by a Gradle software project.
 *
 * <p>An implementation of this interface may also implement:</p>
 *
 * <ul>
 *
 * <li>{@link ComponentWithVariants} to provide information about the variants that the component provides.</li>
 *
 * </ul>
 */
@HasInternalProtocol
public interface SoftwareComponent extends Named {
}
