//package com.tyron.builder.api.internal.artifacts.configurations;
//
//import com.tyron.builder.api.Action;
//import com.tyron.builder.api.artifacts.DependencySubstitution;
//import com.tyron.builder.api.artifacts.ResolutionStrategy;
//import com.tyron.builder.api.internal.artifacts.ComponentSelectionRulesInternal;
//import com.tyron.builder.api.internal.artifacts.configurations.dynamicversion.CachePolicy;
//import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyLockingProvider;
//import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionsInternal;
//import com.tyron.builder.api.internal.artifacts.ivyservice.resolutionstrategy.CapabilitiesResolutionInternal;
//
//public interface ResolutionStrategyInternal extends ResolutionStrategy {
//
//    /**
//     * Gets the current expiry policy for dynamic revisions.
//     *
//     * @return the expiry policy
//     */
//    CachePolicy getCachePolicy();
//
//    /**
//     * Until the feature 'settles' and we receive more feedback, it's internal
//     *
//     * @return conflict resolution
//     */
//    ConflictResolution getConflictResolution();
//
//    /**
//     * @return the dependency substitution rule (may aggregate multiple rules)
//     */
//    Action<DependencySubstitution> getDependencySubstitutionRule();
//
//    /**
//     * Used by tests to validate behaviour of the 'task graph modified' state
//     */
//    void assumeFluidDependencies();
//
//    /**
//     * Should the configuration be fully resolved to determine the task dependencies?
//     * If not, we do a shallow 'resolve' of SelfResolvingDependencies only.
//     */
//    boolean resolveGraphToDetermineTaskDependencies();
//
//    SortOrder getSortOrder();
//
//    @Override
//    DependencySubstitutionsInternal getDependencySubstitution();
//
//    /**
//     * @return the version selection rules object
//     */
//    @Override
//    ComponentSelectionRulesInternal getComponentSelection();
//
//    /**
//     * @return copy of this resolution strategy. See the contract of {@link com.tyron.builder.api.artifacts.Configuration#copy()}.
//     */
//    ResolutionStrategyInternal copy();
//
//    /**
//     * Sets the validator to invoke before mutation. Any exception thrown by the action will veto the mutation.
//     */
//    void setMutationValidator(MutationValidator action);
//
//    /**
//     * Returns the dependency locking provider linked to this resolution strategy.
//     *
//     * @return dependency locking provider
//     */
//    DependencyLockingProvider getDependencyLockingProvider();
//
//    /**
//     * Indicates if dependency locking is enabled.
//     *
//     * @return {@code true} if dependency locking is enabled, {@code false} otherwise
//     */
//    boolean isDependencyLockingEnabled();
//
//    /**
//     * Confirms that an unlocked configuration has been resolved.
//     * This allows the lock state for said configuration to be dropped if it existed before.
//     *
//     * @param configurationName the unlocked configuration
//     */
//    void confirmUnlockedConfigurationResolved(String configurationName);
//
//    CapabilitiesResolutionInternal getCapabilitiesResolutionRules();
//
//    boolean isFailingOnDynamicVersions();
//
//    boolean isFailingOnChangingVersions();
//
//    boolean isDependencyVerificationEnabled();
//}
