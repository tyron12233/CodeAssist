package com.tyron.builder.api.component;

import com.tyron.builder.api.Named;
import com.tyron.builder.internal.HasInternalProtocol;

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
