package com.tyron.builder.api.artifacts;

import java.util.Set;

/**
 * To model a module in your dependency declarations. Usually you can either declare a single dependency
 * artifact or you declare a module dependency that depends on a module descriptor in a repository. With
 * a client module you can declare a module dependency without the need of a module descriptor in a
 * remote repository.
 */
public interface ClientModule extends ExternalModuleDependency {
    /**
     * Add a dependency to the client module. Such a dependency is transitive dependency for the
     * project that has a dependency on the client module.
     *  
     * @param dependency The dependency to add to the client module.
     * @see #getDependencies() 
     */
    void addDependency(ModuleDependency dependency);

    /**
     * Returns the id of the client module. This is usually only used for internal handling of the
     * client module.
     *
     * @return The id of the client module
     */
    String getId();

    /**
     * Returns all the dependencies added to the client module.
     *
     * @see #addDependency(ModuleDependency)
     */
    Set<ModuleDependency> getDependencies();

    /**
     * {@inheritDoc}
     */
    @Override
    ClientModule copy();
}
