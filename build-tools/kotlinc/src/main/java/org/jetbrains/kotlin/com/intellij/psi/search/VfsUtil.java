package org.jetbrains.kotlin.com.intellij.psi.search;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ModuleRootManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Arrays;
import java.util.Collections;

public class VfsUtil {

    @Nullable
    public static Module findModuleForFile(Project project, VirtualFile file) {
        return Arrays.stream(ModuleManager.getInstance(project).getModules()).filter(it -> {
            ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(it);
            return VfsUtilCore.isUnder(file,
                    ContainerUtil.immutableSet(moduleRootManager.getContentRoots()));
        }).findAny().orElse(null);
    }

    public static VirtualFile getContentRootForFile(Project project, VirtualFile file) {
        Module module = findModuleForFile(project, file);
        if (module == null) {
            return null;
        }

        ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        return Arrays.stream(moduleRootManager.getContentRoots())
                .filter(it -> VfsUtilCore.isAncestor(it, file, false))
                .findAny()
                .orElse(null);
    }
}
