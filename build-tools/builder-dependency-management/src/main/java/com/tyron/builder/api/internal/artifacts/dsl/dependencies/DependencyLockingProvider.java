//package com.tyron.builder.api.internal.artifacts.dsl.dependencies;
//
//import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
//import com.tyron.builder.api.artifacts.dsl.LockMode;
//import com.tyron.builder.api.file.RegularFileProperty;
//import com.tyron.builder.api.provider.ListProperty;
//import com.tyron.builder.api.provider.Property;
//
//import java.util.Set;
//
///**
// * Provides dependency locking support for dependency configuration resolution.
// */
//public interface DependencyLockingProvider {
//
//    /**
//     * Loads the lock state associated to the given configuration.
//     *
//     * @param configurationName the configuration to load lock state for
//     *
//     * @return the lock state of the configuration
//     * @throws com.tyron.builder.internal.locking.MissingLockStateException If the {@code LockMode} is {@link LockMode#STRICT} but no lock state can be found.
//     */
//    DependencyLockingState loadLockState(String configurationName);
//
//    /**
//     * Records the resolution result for a locked configuration.
//     *
//     * @param configurationName the configuration that was resolved
//     * @param resolutionResult the resolution result information necessary for locking
//     * @param changingResolvedModules any modules that are resolved and marked as changing which defeats locking purpose
//     */
//    void persistResolvedDependencies(String configurationName, Set<ModuleComponentIdentifier> resolutionResult, Set<ModuleComponentIdentifier> changingResolvedModules);
//
//    /**
//     * The current locking mode, exposed in the {@link com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler}.
//     *
//     * @return the locking mode
//     */
//    Property<LockMode> getLockMode();
//
//    /**
//     * Build finished hook for persisting per project lockfile
//     */
//    void buildFinished();
//
//    /**
//     * The file to be used as the per project lock file, exposed in the {@link DefaultDependencyHandler}.
//     *
//     * @return the lock file
//     */
//    RegularFileProperty getLockFile();
//
//    /**
//     * A list of module identifiers that are to be ignored in the lock state, exposed in the {@link com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler}.
//     * @return
//     */
//    ListProperty<String> getIgnoredDependencies();
//
//    /**
//     * Confirms that a configuration is not locked.
//     * This allows the lock state for said configuration to be dropped if it existed before.
//     *
//     * @param configurationName the unlocked configuration
//     */
//    void confirmConfigurationNotLocked(String configurationName);
//}
