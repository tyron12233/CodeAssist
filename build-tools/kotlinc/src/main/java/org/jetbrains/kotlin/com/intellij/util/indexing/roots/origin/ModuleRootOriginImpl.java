package org.jetbrains.kotlin.com.intellij.util.indexing.roots.origin;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.ModuleRootOrigin;

import java.util.List;

public class ModuleRootOriginImpl implements ModuleRootOrigin {

    private final Module module;
    private final List<VirtualFile> roots;

    public ModuleRootOriginImpl(Module module, List<VirtualFile> roots) {
        this.module = module;
        this.roots = roots;
    }

    @NonNull
    @Override
    public Module getModule() {
        return module;
    }

    @NonNull
    @Override
    public List<VirtualFile> getRoots() {
        return roots;
    }
}
