package com.tyron.completion.xml.v2.project;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.xml.v2.aar.AarResourceRepository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class AppResourceRepository extends MultiResourceRepository {

    static final Key<Boolean> TEMPORARY_RESOURCE_CACHE = Key.create("TemporaryResourceCache");

    private final AndroidModule myFacet;
    private final Object RESOURCE_MAP_LOCK = new Object();

    @NotNull
    static AppResourceRepository create(@NotNull AndroidModule facet, @NotNull Collection<AarResourceRepository> libraryRepositories) {
        //        AndroidProjectRootListener.ensureSubscribed(facet.getModule().getProject());

        return new AppResourceRepository(facet, computeLocalRepositories(facet), libraryRepositories);
    }

    /**
     * Resource directories. Computed lazily.
     */
    @Nullable
    private Collection<File> myResourceDirs;

    @NotNull
    Collection<File> getAllResourceDirs() {
        synchronized (RESOURCE_MAP_LOCK) {
            if (myResourceDirs == null) {
                ImmutableList.Builder<File> result = ImmutableList.builder();
                for (LocalResourceRepository resourceRepository : getLocalResources()) {
                    result.addAll(resourceRepository.getResourceDirs());
                }
                myResourceDirs = result.build();
            }
            return myResourceDirs;
        }
    }

    private static List<LocalResourceRepository> computeLocalRepositories(@NotNull AndroidModule facet) {
        return ImmutableList.of(ResourceRepositoryManager.getProjectResources(facet));
    }

    private AppResourceRepository(@NotNull AndroidModule androidModule,
                                  @NotNull List<LocalResourceRepository> localResources,
                                  @NotNull Collection<AarResourceRepository> libraryResources) {
        super(androidModule.getName() + " with modules and libraries");
        myFacet = androidModule;
        setChildren(localResources, libraryResources, ImmutableList.of());
    }

    void updateRoots(@NotNull Collection<? extends AarResourceRepository> libraryResources) {
        List<LocalResourceRepository> localResources = computeLocalRepositories(myFacet);
        updateRoots(localResources, libraryResources);
    }

    @VisibleForTesting
    void updateRoots(@NotNull List<? extends LocalResourceRepository> localResources,
                     @NotNull Collection<? extends AarResourceRepository> libraryResources) {
        synchronized (RESOURCE_MAP_LOCK) {
            myResourceDirs = null;
        }
        invalidateResourceDirs();
        setChildren(localResources, libraryResources, ImmutableList.of());
    }
}
