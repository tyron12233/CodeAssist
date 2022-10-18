package com.tyron.completion.xml.v2.project;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.project.api.AndroidModule;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @see ResourceRepositoryManager#getProjectResources()
 */
final class ProjectResourceRepository extends MultiResourceRepository {

    private final AndroidModule myFacet;

    private ProjectResourceRepository(@NotNull AndroidModule facet, @NotNull List<LocalResourceRepository> localResources) {
        super(facet.getName() + " with modules");
        myFacet = facet;
        setChildren(localResources, ImmutableList.of(), ImmutableList.of());
    }

    @NotNull
    public static ProjectResourceRepository create(@NotNull AndroidModule facet) {
        List<LocalResourceRepository> resources = computeRepositories(facet);
        return new ProjectResourceRepository(facet, resources);
    }

    @NotNull
    private static List<LocalResourceRepository> computeRepositories(@NotNull AndroidModule facet) {
        LocalResourceRepository main = ResourceRepositoryManager.getModuleResources(facet);

        Set<String> dependencies = facet.getModuleDependencies();
        if (dependencies.isEmpty()) {
            return ImmutableList.of(main);
        }

        List<LocalResourceRepository> resources = new ArrayList<>(dependencies.size() + 1);
        resources.add(main);
        return resources;
    }

    void updateRoots() {
        List<LocalResourceRepository> repositories = computeRepositories(myFacet);
        invalidateResourceDirs();
        setChildren(repositories, ImmutableList.of(), ImmutableList.of());
    }
}
