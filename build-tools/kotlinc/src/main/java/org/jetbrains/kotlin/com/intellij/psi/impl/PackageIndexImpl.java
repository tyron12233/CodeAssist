package org.jetbrains.kotlin.com.intellij.psi.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.PackageIndex;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.CollectionQuery;
import org.jetbrains.kotlin.com.intellij.util.Query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PackageIndexImpl extends PackageIndex {

    private final Project project;

    public PackageIndexImpl(Project project) {
        this.project = project;
    }

    private List<VirtualFile> findDirectoriesByPackageName(String packageName) {
        List<VirtualFile> result = new ArrayList<>();
        String dirName = packageName.replace(".", "/");

        return Arrays.stream(ModuleManager.getInstance(project).getModules())
                .map(ModuleRootManager::getInstance)
                .flatMap(moduleRootManager -> Arrays.stream(moduleRootManager.getContentRoots()))
                .map(root -> root.findFileByRelativePath(dirName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @NonNull
    @Override
    public Query<VirtualFile> getDirsByPackageName(@NonNull String packageName,
                                                            boolean includeLibrarySources) {

        return new CollectionQuery<>(findDirectoriesByPackageName(packageName));
    }
}
