package org.jetbrains.kotlin.com.intellij.util.indexing.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.IndexableSetOrigin;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.origin.ModuleRootOriginImpl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ModuleIndexableFilesIteratorImpl implements ModuleIndexableFilesIterator {

    private final Module module;
    private final List<VirtualFile> roots;
    private final boolean printRootsInDebugName;

    public ModuleIndexableFilesIteratorImpl(Module module,
                                            List<VirtualFile> roots,
                                            boolean printRootsInDebugName) {
        this.module = module;
        this.roots = roots;
        this.printRootsInDebugName = printRootsInDebugName;

        assert !roots.isEmpty();
    }

    @Override
    public String getDebugName() {
        if (printRootsInDebugName) {
            return roots.stream()
                    .map(VirtualFile::getName)
                    .sorted()
                    .collect(Collectors.joining(", "));
        }
        return "Module " + module.getName();
    }

    @Override
    public String getIndexingProgressText() {
        return "Indexing module " + module.getName();
    }

    @Override
    public String getRootsScanningProgressText() {
        return "Scanning module roots: " + module.getName();
    }

    @NonNull
    @Override
    public IndexableSetOrigin getOrigin() {
        return new ModuleRootOriginImpl(module, roots);
    }

    @Override
    public boolean iterateFiles(@NonNull Project project,
                                @NonNull ContentIterator fileIterator,
                                @NonNull VirtualFileFilter filter) {
        ModuleFileIndex index = ReadAction.compute(() -> {
            if (module.isDisposed()) {
                return null;
            }
            return ModuleRootManager.getInstance(module).getFileIndex();
        });
        if (index == null) {
            return false;
        }
        for (VirtualFile root : roots) {
            index.iterateContentUnderDirectory(root, fileIterator, filter);
        }
        return true;
    }

    @NonNull
    @Override
    public Set<String> getRootUrls(@NonNull Project project) {
        return new HashSet<>(Arrays.asList(ModuleRootManager.getInstance(module)
                .getContentRootUrls()));
    }
}
