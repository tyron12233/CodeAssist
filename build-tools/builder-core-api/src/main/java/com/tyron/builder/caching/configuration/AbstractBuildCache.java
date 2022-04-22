package com.tyron.builder.caching.configuration;

/**
 * Base implementation for build cache service configuration.
 *
 * @since 3.5
 */
public abstract class AbstractBuildCache implements BuildCache {
    private boolean enabled = true;
    private boolean push;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPush() {
        return push;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPush(boolean push) {
        this.push = push;
    }
}