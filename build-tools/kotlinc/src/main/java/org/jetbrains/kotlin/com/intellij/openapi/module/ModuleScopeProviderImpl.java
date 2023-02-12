package org.jetbrains.kotlin.com.intellij.openapi.module;

import static org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil.createConcurrentIntObjectMap;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.containers.IntObjectMap;

public class ModuleScopeProviderImpl implements ModuleScopeProvider {

    private final Module myModule;
    private final IntObjectMap<GlobalSearchScope> myScopeCache =
            createConcurrentIntObjectMap();
    private ModuleWithDependentsTestScope myModuleTestsWithDependentsScope;

    public ModuleScopeProviderImpl(@NonNull Module module) {
        myModule = module;
    }

    @NonNull
    private GlobalSearchScope getCachedScope(@ModuleWithDependenciesScope.ScopeConstant int options) {
        GlobalSearchScope scope = myScopeCache.get(options);
        if (scope == null) {
            scope = new ModuleWithDependenciesScope(myModule, options);
            myScopeCache.put(options, scope);
        }
        return scope;
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleScope() {
        return getCachedScope(ModuleWithDependenciesScope.COMPILE_ONLY | ModuleWithDependenciesScope.TESTS);
    }

    @NonNull
    @Override
    public GlobalSearchScope getModuleScope(boolean includeTests) {
        return getCachedScope(ModuleWithDependenciesScope.COMPILE_ONLY | (includeTests ? ModuleWithDependenciesScope.TESTS : 0));
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleWithLibrariesScope() {
        return getCachedScope(ModuleWithDependenciesScope.COMPILE_ONLY | ModuleWithDependenciesScope.TESTS | ModuleWithDependenciesScope.LIBRARIES);
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleWithDependenciesScope() {
        return getCachedScope(ModuleWithDependenciesScope.COMPILE_ONLY | ModuleWithDependenciesScope.TESTS | ModuleWithDependenciesScope.MODULES);
    }

    @NonNull
    @Override
    public GlobalSearchScope getModuleContentScope() {
        return getCachedScope(ModuleWithDependenciesScope.CONTENT);
    }

    @NonNull
    @Override
    public GlobalSearchScope getModuleContentWithDependenciesScope() {
        return getCachedScope(ModuleWithDependenciesScope.CONTENT | ModuleWithDependenciesScope.MODULES);
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleWithDependenciesAndLibrariesScope(boolean includeTests) {
        return getCachedScope(ModuleWithDependenciesScope.COMPILE_ONLY |
                              ModuleWithDependenciesScope.MODULES |
                              ModuleWithDependenciesScope.LIBRARIES | (includeTests ? ModuleWithDependenciesScope.TESTS : 0));
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleWithDependentsScope() {
        return getModuleTestsWithDependentsScope().getDelegate();
    }

    @Override
    @NonNull
    public ModuleWithDependentsTestScope getModuleTestsWithDependentsScope() {
        ModuleWithDependentsTestScope scope = myModuleTestsWithDependentsScope;
        if (scope == null) {
            myModuleTestsWithDependentsScope = scope = new ModuleWithDependentsTestScope(myModule);
        }
        return scope;
    }

    @Override
    @NonNull
    public GlobalSearchScope getModuleRuntimeScope(boolean includeTests) {
        return getCachedScope(
                ModuleWithDependenciesScope.MODULES | ModuleWithDependenciesScope.LIBRARIES | (includeTests ? ModuleWithDependenciesScope.TESTS : 0));
    }

    @Override
    public @NonNull GlobalSearchScope getModuleProductionSourceScope() {
        return getCachedScope(0);
    }

    @Override
    public @NonNull GlobalSearchScope getModuleTestSourceScope() {
        return getCachedScope(ModuleWithDependenciesScope.TESTS);
    }

    @Override
    public void clearCache() {
        myScopeCache.entrySet().clear();
        myModuleTestsWithDependentsScope = null;
    }
}
