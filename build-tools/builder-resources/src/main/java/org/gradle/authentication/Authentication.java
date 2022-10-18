package org.gradle.authentication;

import org.gradle.api.Named;
import org.gradle.internal.HasInternalProtocol;

/**
 * Base interface for transport authentication schemes.
 */
@HasInternalProtocol
public interface Authentication extends Named {
}
