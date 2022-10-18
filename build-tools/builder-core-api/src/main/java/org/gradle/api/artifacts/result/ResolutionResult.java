package org.gradle.api.artifacts.result;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.provider.Provider;
import org.gradle.internal.scan.UsedByScanPlugin;

import java.util.Set;

import groovy.lang.Closure;

/**
 * Contains the information about the result of dependency resolution. You can use this type to determine all the component instances that are included
 * in the resolved dependency graph, and the dependencies between them.
 */
@UsedByScanPlugin
public interface ResolutionResult {

    /**
     * Gives access to the root of resolved dependency graph.
     * You can walk the graph recursively from the root to obtain information about resolved dependencies.
     * For example, Gradle's built-in 'dependencies' task uses this to render the dependency tree.
     *
     * @return the root node of the resolved dependency graph
     */
    ResolvedComponentResult getRoot();

    /**
     * Returns the root of resolved dependency graph as a {@link Provider} of {@link ResolvedComponentResult}.
     * The returned {@link Provider} is live, and tracks the producer tasks of this resolution result.
     * The provider will resolve the component metadata as required.
     *
     * You can walk the graph recursively from the root to obtain information about resolved dependencies.
     * For example, Gradle's built-in 'dependencies' task uses this to render the dependency tree.
     *
     * @return a provider for the root node of the resolved dependency graph
     * @since 7.4
     */
    @Incubating
    Provider<ResolvedComponentResult> getRootComponent();

    /**
     * Retrieves all dependencies, including unresolved dependencies.
     * Resolved dependencies are represented by instances of {@link ResolvedDependencyResult},
     * unresolved dependencies by {@link UnresolvedDependencyResult}.
     *
     * In dependency graph terminology, this method returns the edges of the graph.
     *
     * @return all dependencies, including unresolved dependencies.
     */
    Set<? extends DependencyResult> getAllDependencies();

    /**
     * Applies given action for each dependency.
     * An instance of {@link DependencyResult} is passed as parameter to the action.
     *
     * @param action - action that is applied for each dependency
     */
    void allDependencies(Action<? super DependencyResult> action);

    /**
     * Applies given closure for each dependency.
     * An instance of {@link DependencyResult} is passed as parameter to the closure.
     *
     * @param closure - closure that is applied for each dependency
     */
    void allDependencies(Closure closure);

    /**
     * Retrieves all instances of {@link ResolvedComponentResult} from the graph,
     * e.g. all nodes of the dependency graph.
     *
     * @return all nodes of the dependency graph.
     */
    Set<ResolvedComponentResult> getAllComponents();

    /**
     * Applies given action for each component.
     * An instance of {@link ResolvedComponentResult} is passed as parameter to the action.
     *
     * @param action - action that is applied for each component
     */
    void allComponents(Action<? super ResolvedComponentResult> action);

    /**
     * Applies given closure for each component.
     * An instance of {@link ResolvedComponentResult} is passed as parameter to the closure.
     *
     * @param closure - closure that is applied for each component
     */
    void allComponents(Closure closure);

    /**
     * The attributes that were requested. Those are the attributes which
     * are used during variant aware resolution, to select the variants.
     * Attributes returned by this method are <i>desugared</i>, meaning that
     * they have lost their rich types and can only be of type Boolean or String.
     *
     * @since 5.6
     */
    AttributeContainer getRequestedAttributes();
}
