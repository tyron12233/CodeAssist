package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;

/**
 * <p>Descriptor of a repository content, used to avoid reaching to
 * an external repository when not needed.</p>
 *
 * <p>Only inclusions can be defined at this level (cross-repository content).
 * Excludes need to be defined per-repository using {@link RepositoryContentDescriptor}.
 * Similarly to declaring includes on specific repositories via {@link ArtifactRepository#content(Action)},
 * inclusions are <i>extensive</i>, meaning that anything which doesn't match the includes will be
 * considered missing from the repository.
 * </p>
 *
 * @since 6.2
 */
public interface InclusiveRepositoryContentDescriptor {
    /**
     * Declares that an entire group should be searched for in this repository.
     *
     * @param group the group name
     */
    void includeGroup(String group);

    /**
     * Declares that an entire group should be searched for in this repository.
     *
     * @param groupRegex a regular expression of the group name
     */
    void includeGroupByRegex(String groupRegex);

    /**
     * Declares that an entire module should be searched for in this repository.
     *
     * @param group the group name
     * @param moduleName the module name
     */
    void includeModule(String group, String moduleName);

    /**
     * Declares that an entire module should be searched for in this repository, using regular expressions.
     *
     * @param groupRegex the group name regular expression
     * @param moduleNameRegex the module name regular expression
     */
    void includeModuleByRegex(String groupRegex, String moduleNameRegex);

    /**
     * Declares that a specific module version should be searched for in this repository.
     *
     * @param group the group name
     * @param moduleName the module name
     * @param version the module version
     */
    void includeVersion(String group, String moduleName, String version);

    /**
     * Declares that a specific module version should be searched for in this repository, using regular expressions.
     *
     * @param groupRegex the group name regular expression
     * @param moduleNameRegex the module name regular expression
     * @param versionRegex the module version regular expression
     */
    void includeVersionByRegex(String groupRegex, String moduleNameRegex, String versionRegex);
}
