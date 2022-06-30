package com.tyron.builder.caching.internal;

import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * The transformation of the user's build cache config, to the effective configuration.
 *
 * This operation should occur some time after the configuration phase.
 * In practice, it will fire as part of bootstrapping the execution of the first task to execute.
 *
 * This operation should always be executed, regardless of whether caching is enabled/disabled.
 * That is, determining enabled-ness is part of “finalizing”.
 * However, if the build fails during configuration or task graph assembly, it will not be emitted.
 * It must fire before any build cache is used.
 *
 * See BuildCacheControllerFactory.
 *
 * @since 4.0
 */
public final class FinalizeBuildCacheConfigurationBuildOperationType implements BuildOperationType<FinalizeBuildCacheConfigurationBuildOperationType.Details, FinalizeBuildCacheConfigurationBuildOperationType.Result> {

    public interface Details {

        /**
         * The path to the build that the build cache configuration is associated with.
         *
         * @since 4.5
         */
        String getBuildPath();

    }

    public interface Result {

        boolean isEnabled();

        boolean isLocalEnabled();

        boolean isRemoteEnabled();

        @Nullable
        BuildCacheDescription getLocal();

        @Nullable
        BuildCacheDescription getRemote();

        interface BuildCacheDescription {

            /**
             * The class name of the DSL configuration type.
             *
             * e.g. {@link com.tyron.builder.caching.local.DirectoryBuildCache}
             */
            String getClassName();

            /**
             * The human friendly description of the type (e.g. "HTTP", "directory")
             *
             * @see com.tyron.builder.caching.BuildCacheServiceFactory.Describer#type(String)
             */
            String getType();

            /**
             * Whether push was enabled.
             */
            boolean isPush();

            /**
             * The advertised config parameters of the cache.
             * No null values or keys.
             * Ordered by key lexicographically.
             *
             * @see com.tyron.builder.caching.BuildCacheServiceFactory.Describer#config(String, String)
             */
            Map<String, String> getConfig();

        }

    }

    private FinalizeBuildCacheConfigurationBuildOperationType() {
    }

}
