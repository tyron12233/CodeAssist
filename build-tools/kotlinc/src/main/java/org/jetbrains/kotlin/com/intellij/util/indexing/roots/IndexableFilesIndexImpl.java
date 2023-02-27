package org.jetbrains.kotlin.com.intellij.util.indexing.roots;

import static org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;
import org.jetbrains.kotlin.com.intellij.util.NotNullFunction;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.indexing.AdditionalIndexableFileSet;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableFilesIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.IndexableSetOrigin;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.SdkOrigin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class IndexableFilesIndexImpl implements IndexableFilesIndex {

    @NotNull
    private final Project project;
    private final AdditionalIndexableFileSet filesFromIndexableSetContributors;

    @NotNull
    public static IndexableFilesIndexImpl getInstanceImpl(@NotNull Project project) {
        return (IndexableFilesIndexImpl) IndexableFilesIndex.getInstance(project);
    }


    public IndexableFilesIndexImpl(@NotNull Project project) {
        this.project = project;
        filesFromIndexableSetContributors = new AdditionalIndexableFileSet(project);
    }

    @Override
    public boolean shouldBeIndexed(@NotNull VirtualFile file) {
        boolean workspaceFile = getIndexingIterators().stream()
                .anyMatch(it -> !it.iterateFiles(project,
                        fileOrDir -> !VfsUtilCore.isAncestor(fileOrDir, file, false),
                        VirtualFile::isDirectory));
        if (workspaceFile) {
            return true;
        }
        return filesFromIndexableSetContributors.isInSet(file);
    }

    @Override
    public @NotNull List<IndexableFilesIterator> getIndexingIterators() {
        return ReadAction.compute(this::doGetIndexingIterators);
    }

    @NotNull
    private List<IndexableFilesIterator> doGetIndexingIterators() {
        List<IndexableFilesIterator> iterators = new ArrayList<>();

        Sdk defaultSdk = SdkManager.getInstance(project).getDefaultSdk();
        iterators.add(new IndexableFilesIterator() {

            final SdkOrigin origin = new SdkOrigin() {

                @NonNull
                @Override
                public Collection<VirtualFile> getRootsToIndex() {
                    VirtualFileSystem fs = StandardFileSystems.jar();
                    return defaultSdk.getJarFiles()
                            .stream()
                            .map(it -> fs.findFileByPath(it.getPath() + "!/"))
                            .collect(Collectors.toList());
                }

                @NonNull
                @Override
                public Sdk getSdk() {
                    return defaultSdk;
                }
            };

            @Override
            public String getDebugName() {
                return defaultSdk.getName();
            }

            @Override
            public String getIndexingProgressText() {
                return "Indexing " + defaultSdk.getName();
            }

            @Override
            public String getRootsScanningProgressText() {
                return "Scanning roots for " + defaultSdk.getName();
            }

            @NonNull
            @Override
            public IndexableSetOrigin getOrigin() {
                return origin;
            }

            @Override
            public boolean iterateFiles(@NonNull Project project,
                                        @NonNull ContentIterator iterator,
                                        @NonNull VirtualFileFilter filter) {
                Collection<VirtualFile> rootsToIndex = origin.getRootsToIndex();
                Set<VirtualFile> set = new HashSet<>(rootsToIndex);
                return set.stream().allMatch(root -> {
                    VirtualFileVisitor.Result result =
                            visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
                                @Override
                                public @NotNull Result visitFileEx(@NotNull VirtualFile file) {
                                    if (!filter.accept(file)) {
                                        return SKIP_CHILDREN;
                                    }
                                    if (!iterator.processFile(file)) {
                                        return skipTo(root);
                                    }
                                    return CONTINUE;
                                }
                            });
                    return !Comparing.equal(result.skipToParent, root);
                });
            }

            @NonNull
            @Override
            public Set<String> getRootUrls(@NonNull Project project) {
                return origin.getRootsToIndex()
                        .stream()
                        .map(VirtualFile::getUrl)
                        .collect(Collectors.toSet());
            }
        });
        return iterators;
    }

    @Override
    public @NotNull Collection<IndexableFilesIterator> getModuleIndexingIterators(@NotNull Module module) {
        List<VirtualFile> roots = getModuleRootsToIndex(module);
        if (roots.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ModuleIndexableFilesIteratorImpl(module, roots, true));
    }

    @NotNull
    private List<VirtualFile> getModuleRootsToIndex(@NotNull Module module) {
        return ReadAction.compute(() -> {
            if (project.isDisposed()) {
                return Collections.emptyList();
            }
            List<VirtualFile> files = new SmartList<>();
            ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);

            VirtualFile[] roots = moduleRootManager.orderEntries()
                    .withoutSdk()
                    .compileOnly()
                    .recursively()
                    .roots(orderEntry -> {
                        if (orderEntry instanceof ModuleSourceOrderEntry) {
                            return OrderRootType.SOURCES;
                        }
                        return OrderRootType.CLASSES;
                    })
                    .getRoots();
            Collections.addAll(files, roots);
            return files;
        });
    }
}
