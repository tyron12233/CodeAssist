package com.tyron.builder.caching;

import com.tyron.builder.api.BuildException;

/**
 * <p><code>BuildCacheException</code> is the base class of all exceptions thrown by a {@link BuildCacheService}.</p>
 *
 * @since 3.3
 */
public class BuildCacheException extends BuildException {
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