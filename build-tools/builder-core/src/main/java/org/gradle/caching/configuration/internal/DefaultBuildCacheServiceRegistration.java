package org.gradle.caching.configuration.internal;

import org.gradle.caching.BuildCacheServiceFactory;
import org.gradle.caching.configuration.BuildCache;

public class DefaultBuildCacheServiceRegistration implements BuildCacheServiceRegistration {
    private final Class<? extends BuildCacheServiceFactory<?>> factoryType;
    private final Class<? extends BuildCache> configurationType;

    public DefaultBuildCacheServiceRegistration(Class<? extends BuildCache> configurationType, Class<? extends BuildCacheServiceFactory<?>> factoryType) {
        this.factoryType = factoryType;
        this.configurationType = configurationType;
    }

    @Override
    public Class<? extends BuildCache> getConfigurationType() {
        return configurationType;
    }

    @Override
    public Class<? extends BuildCacheServiceFactory<?>> getFactoryType() {
        return factoryType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultBuildCacheServiceRegistration that = (DefaultBuildCacheServiceRegistration) o;

        if (!factoryType.equals(that.factoryType)) {
            return false;
        }
        return configurationType.equals(that.configurationType);
    }

    @Override
    public int hashCode() {
        int result = factoryType.hashCode();
        result = 31 * result + configurationType.hashCode();
        return result;
    }
}
