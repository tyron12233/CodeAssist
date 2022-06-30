package com.tyron.builder.internal.buildtree;


import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.BuildTree.class)
public class BuildModelParameters {
    private final boolean configureOnDemand;
    private final boolean configurationCache;
    private final boolean isolatedProjects;
    private final boolean requiresBuildModel;
    private final boolean intermediateModelCache;
    private final boolean parallelToolingApiActions;
    private final boolean invalidateCoupledProjects;

    public BuildModelParameters(
            boolean configureOnDemand,
            boolean configurationCache,
            boolean isolatedProjects,
            boolean requiresBuildModel,
            boolean intermediateModelCache,
            boolean parallelToolingApiActions,
            boolean invalidateCoupledProjects
    ) {
        this.configureOnDemand = configureOnDemand;
        this.configurationCache = configurationCache;
        this.isolatedProjects = isolatedProjects;
        this.requiresBuildModel = requiresBuildModel;
        this.intermediateModelCache = intermediateModelCache;
        this.parallelToolingApiActions = parallelToolingApiActions;
        this.invalidateCoupledProjects = invalidateCoupledProjects;
    }

    /**
     * Will the build model, that is the configured Gradle and Project objects, be required during the build execution?
     *
     * <p>When the build model is not required, certain state can be discarded or not created.
     */
    public boolean isRequiresBuildModel() {
        return requiresBuildModel;
    }

    public boolean isConfigureOnDemand() {
        return configureOnDemand;
    }

    public boolean isConfigurationCache() {
        return configurationCache;
    }

    public boolean isIsolatedProjects() {
        return isolatedProjects;
    }

    /**
     * When {@link  #isIsolatedProjects()} is true, should intermediate tooling models be cached?
     * This is currently true when fetching a tooling model, otherwise false.
     */
    public boolean isIntermediateModelCache() {
        return intermediateModelCache;
    }

    /**
     * Force parallel tooling API actions? When true, always use parallel execution, when false use a default value.
     */
    public boolean isParallelToolingApiActions() {
        return parallelToolingApiActions;
    }

    /**
     * When {@link  #isIsolatedProjects()} is true, should project state be invalidated when a project it is coupled with changes?
     * This parameter is only used for benchmarking purposes.
     */
    public boolean isInvalidateCoupledProjects() {
        return invalidateCoupledProjects;
    }
}