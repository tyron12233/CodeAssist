package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.DelegatingGlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

// Tests only (module plus dependencies) scope
// Delegates to ModuleWithDependentsScope with extra flag testOnly to reduce memory for holding
// modules and CPU for traversing dependencies.
class ModuleWithDependentsTestScope extends DelegatingGlobalSearchScope {
    ModuleWithDependentsTestScope(@NonNull Module module) {
        // the additional equality argument allows to distinguish ModuleWithDependentsTestScope
      // from ModuleWithDependentsScope
        super(new ModuleWithDependentsScope(module), true);
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        return ((ModuleWithDependentsScope) myBaseScope).contains(file, true);
    }

    @Override
    public String toString() {
        return "Restricted by tests: (" + myBaseScope + ")";
    }

    public GlobalSearchScope getDelegate() {
        return myBaseScope;
    }
}