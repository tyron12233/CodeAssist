package org.gradle.caching;

import org.gradle.api.GradleException;

/**
 * <p><code>BuildCacheException</code> is the base class of all exceptions thrown by a {@link BuildCacheService}.</p>
 *
 * @since 3.3
 */
public class BuildCacheException extends GradleException {
    public BuildCacheException() {
        super();
    }

    public BuildCacheException(String message) {
        super(message);
    }

    public BuildCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}