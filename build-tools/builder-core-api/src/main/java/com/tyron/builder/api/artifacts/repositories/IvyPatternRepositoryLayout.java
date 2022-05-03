package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;

/**
 * A repository layout that uses user-supplied patterns. Each pattern will be appended to the base URI for the repository.
 * At least one artifact pattern must be specified. If no Ivy patterns are specified, then the artifact patterns will be used.
 * Optionally supports a Maven style layout for the 'organisation' part, replacing any dots with forward slashes.
 *
 * For examples see the reference for {@link com.tyron.builder.api.artifacts.repositories.IvyArtifactRepository#patternLayout(Action)}.
 *
 * @since 2.3 (feature was already present in Groovy DSL, this type introduced in 2.3)
 */
public interface IvyPatternRepositoryLayout extends RepositoryLayout {

    /**
     * Adds an Ivy artifact pattern to define where artifacts are located in this repository.
     * @param pattern The ivy pattern
     */
    void artifact(String pattern);

    /**
     * Adds an Ivy pattern to define where ivy files are located in this repository.
     * @param pattern The ivy pattern
     */
    void ivy(String pattern);

    /**
     * Tells whether a Maven style layout is to be used for the 'organisation' part, replacing any dots with forward slashes.
     * Defaults to {@code false}.
     */
    boolean getM2Compatible();

    /**
     * Sets whether a Maven style layout is to be used for the 'organisation' part, replacing any dots with forward slashes.
     * Defaults to {@code false}.
     *
     * @param m2compatible whether a Maven style layout is to be used for the 'organisation' part
     */
    void setM2compatible(boolean m2compatible);
}
