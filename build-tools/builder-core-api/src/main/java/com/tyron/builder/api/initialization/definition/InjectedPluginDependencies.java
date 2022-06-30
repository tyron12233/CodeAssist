package com.tyron.builder.api.initialization.definition;

/**
 * The DSL for declaring plugins to inject into an included build.
 *
 * @since 4.6
 */
public interface InjectedPluginDependencies {
    /**
     * Add a dependency on the plugin with the given id.
     *
     * @param id the id of the plugin to depend on
     *
     * @return a mutable injected plugin dependency that can be used to further refine the dependency
     */
    InjectedPluginDependency id(String id);
}
