package com.tyron.builder.api.internal.artifacts.configurations;

import com.tyron.builder.api.artifacts.result.ResolvedComponentResult;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.internal.operations.BuildOperationType;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Resolution of a configuration's dependencies.
 *
 * @since 4.4
 */
public final class ResolveConfigurationDependenciesBuildOperationType implements BuildOperationType<ResolveConfigurationDependenciesBuildOperationType.Details, ResolveConfigurationDependenciesBuildOperationType.Result> {

    @UsedByScanPlugin
    public interface Details {

        String getConfigurationName();

        @Nullable
        String getProjectPath();

        boolean isScriptConfiguration();

        @Nullable
        String getConfigurationDescription();

        String getBuildPath();

        boolean isConfigurationVisible();

        boolean isConfigurationTransitive();

        /**
         * @since 4.10
         */
        @Nullable
        List<Repository> getRepositories();

    }

    @UsedByScanPlugin
    public interface Result {

        ResolvedComponentResult getRootComponent();

        /**
         * If the component was resolved from a repository, its {@link Repository#getId()}.
         */
        @Nullable
        String getRepositoryId(ResolvedComponentResult resolvedComponentResult);

        /**
         * Which attributes the resolved configuration was requested with, if any.
         * @since 5.6
         */
        AttributeContainer getRequestedAttributes();
    }

    /**
     * A description of a repository potentially used in the resolution.
     *
     * Effectively maps to a RepositoryDescriptor.
     */
    @UsedByScanPlugin
    public interface Repository {

        /**
         *  A unique identifier for this repository, used for associating resolved components to their source repository.
         */
        String getId();

        /**
         * The type of repository.
         *
         * Then name of one of RepositoryDescriptor.Type.
         */
        String getType();

        /**
         *  The name of this repository.
         */
        String getName();

        /**
         * The properties of the repository.
         *
         * See RepositoryDescriptor.getProperties() and the specific properties used by the subtypes.
         * The properties sent, their names and types, should be kept stable.
         * Ordered by key lexicographically.
         */
        Map<String, ?> getProperties();

    }

}
