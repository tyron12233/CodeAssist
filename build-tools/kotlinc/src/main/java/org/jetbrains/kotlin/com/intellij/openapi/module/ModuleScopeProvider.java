package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

/**
 * Author: dmitrylomov
 */
public interface ModuleScopeProvider {
    /**
     * Returns module scope including sources and tests, excluding libraries and dependencies.
     *
     * @return scope including sources and tests, excluding libraries and dependencies.
     */
    @NonNull
    GlobalSearchScope getModuleScope();

    @NonNull
    GlobalSearchScope getModuleScope(boolean includeTests);

    /**
     * Returns module scope including sources, tests, and libraries, excluding dependencies.
     *
     * @return scope including sources, tests, and libraries, excluding dependencies.
     */
    @NonNull
    GlobalSearchScope getModuleWithLibrariesScope();

    /**
     * Returns module scope including sources, tests, and dependencies, excluding libraries.
     *
     * @return scope including sources, tests, and dependencies, excluding libraries.
     */
    @NonNull
    GlobalSearchScope getModuleWithDependenciesScope();

    @NonNull
    GlobalSearchScope getModuleContentScope();

    @NonNull
    GlobalSearchScope getModuleContentWithDependenciesScope();

    @NonNull
    GlobalSearchScope getModuleWithDependenciesAndLibrariesScope(boolean includeTests);

    @NonNull
    GlobalSearchScope getModuleWithDependentsScope();

    @NonNull
    GlobalSearchScope getModuleTestsWithDependentsScope();

    @NonNull
    GlobalSearchScope getModuleRuntimeScope(boolean includeTests);

    @NonNull
    GlobalSearchScope getModuleProductionSourceScope();

    @NonNull
    GlobalSearchScope getModuleTestSourceScope();

    void clearCache();
}