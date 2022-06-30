//package com.tyron.builder.api.artifacts.configurations;
//
//import com.tyron.builder.api.Action;
//import com.tyron.builder.api.artifacts.Configuration;
//import com.tyron.builder.api.artifacts.DependencyConstraint;
//import com.tyron.builder.api.artifacts.ExcludeRule;
//import com.tyron.builder.api.artifacts.PublishArtifact;
//import com.tyron.builder.api.artifacts.ResolveException;
//import com.tyron.builder.api.capabilities.Capability;
//import com.tyron.builder.api.internal.artifacts.ResolveContext;
//import com.tyron.builder.api.internal.artifacts.transform.ExtraExecutionGraphDependenciesResolverFactory;
//import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
//import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
//import com.tyron.builder.internal.DisplayName;
//import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
//import com.tyron.builder.util.Path;
//
//import javax.annotation.Nullable;
//import java.util.Collection;
//import java.util.List;
//import java.util.Set;
//import java.util.function.Supplier;
//
//public interface ConfigurationInternal extends ResolveContext, Configuration, DeprecatableConfiguration, DependencyMetaDataProvider {
//    enum InternalState {
//        UNRESOLVED,
//        BUILD_DEPENDENCIES_RESOLVED,
//        GRAPH_RESOLVED,
//        ARTIFACTS_RESOLVED
//    }
//
//    @Override
//    ResolutionStrategyInternal getResolutionStrategy();
//
//    @Override
//    AttributeContainerInternal getAttributes();
//
//    String getPath();
//
//    Path getIdentityPath();
//
//    /**
//     * Runs any registered dependency actions for this Configuration, and any parent Configuration.
//     * Actions may mutate the dependency set for this configuration.
//     * After execution, all actions are de-registered, so execution will only occur once.
//     */
//    void runDependencyActions();
//
//    void markAsObserved(InternalState requestedState);
//
//    void addMutationValidator(MutationValidator validator);
//
//    void removeMutationValidator(MutationValidator validator);
//
//    /**
//     * Converts this configuration to an {@link OutgoingVariant} view. The view may not necessarily be immutable.
//     */
//    OutgoingVariant convertToOutgoingVariant();
//
//    /**
//     * Visits the variants of this configuration.
//     */
//    void collectVariants(VariantVisitor visitor);
//
//    /**
//     * Registers an action to execute before locking for further mutation.
//     */
//    void beforeLocking(Action<? super ConfigurationInternal> action);
//
//    void preventFromFurtherMutation();
//
//    /**
//     * Gets the complete set of exclude rules including those contributed by
//     * superconfigurations.
//     */
//    Set<ExcludeRule> getAllExcludeRules();
//
//    ExtraExecutionGraphDependenciesResolverFactory getDependenciesResolver();
//
//    @Nullable
//    ConfigurationInternal getConsistentResolutionSource();
//
//    Supplier<List<DependencyConstraint>> getConsistentResolutionConstraints();
//
//    /**
//     * Decorates a resolve exception with more context. This can be used
//     * to give hints to the user when a resolution error happens.
//     * @param e a resolve exception
//     * @return a decorated resolve exception, or the same exception
//     */
//    ResolveException maybeAddContext(ResolveException e);
//
//    interface VariantVisitor {
//        // The artifacts to use when this configuration is used as a configuration
//        void visitArtifacts(Collection<? extends PublishArtifact> artifacts);
//
//        // This configuration as a variant. May not always be present
//        void visitOwnVariant(DisplayName displayName, ImmutableAttributes attributes, Collection<? extends Capability> capabilities, Collection<? extends PublishArtifact> artifacts);
//
//        // A child variant. May not always be present
//        void visitChildVariant(String name, DisplayName displayName, ImmutableAttributes attributes, Collection<? extends Capability> capabilities, Collection<? extends PublishArtifact> artifacts);
//    }
//}
