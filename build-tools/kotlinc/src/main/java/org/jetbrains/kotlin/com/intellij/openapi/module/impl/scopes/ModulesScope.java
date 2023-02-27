package org.jetbrains.kotlin.com.intellij.openapi.module.impl.scopes;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;

import java.util.Arrays;
import java.util.Set;

public class ModulesScope extends GlobalSearchScope {
    private final FileIndexFacade myProjectFileIndex;
    private final Set<? extends Module> myModules;

    public ModulesScope(@NonNull Set<? extends Module> modules, @NonNull Project project) {
        super(project);
        myProjectFileIndex = FileIndexFacade.getInstance(project);
        myModules = modules;
    }

    @Override
    public boolean contains(@NonNull VirtualFile file) {
        Module moduleOfFile = myProjectFileIndex.getModuleForFile(file);
        return moduleOfFile != null && myModules.contains(moduleOfFile);
    }

    @Override
    public boolean isSearchInModuleContent(@NonNull Module aModule) {
        return myModules.contains(aModule);
    }

    @Override
    public boolean isSearchInLibraries() {
        return false;
    }

    @Override
    public String toString() {
        return "Modules:" + Arrays.toString(myModules.toArray());
    }
}