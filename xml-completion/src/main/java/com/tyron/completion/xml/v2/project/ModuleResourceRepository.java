package com.tyron.completion.xml.v2.project;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceVisitor;
import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.ide.common.resources.SourceSet;
import com.android.resources.ResourceType;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.tyron.builder.project.api.AndroidContentRoot;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ModuleResourceRepository extends MultiResourceRepository implements SingleNamespaceResourceRepository {

    @NotNull private final AndroidModule myFacet;
    @NotNull private final ResourceNamespace myNamespace;
    @NotNull private final SourceSet mySourceSet;
//    @NotNull private final ResourceFolderRegistry myRegistry;

    private enum SourceSet { MAIN, TEST }

    /**
     * Creates a new resource repository for the given module, <b>not</b> including its dependent
     * modules.
     *
     * <p>The returned repository needs to be registered with a
     * {@link com.intellij.openapi.Disposable} parent.
     *
     * @param facet     the facet for the module
     * @param namespace the namespace for the repository
     * @return the resource repository
     */
    @NotNull
    static ModuleResourceRepository forMainResources(@NotNull AndroidModule facet,
                                                    @NotNull ResourceNamespace namespace) {
        List<File> resourceDirectories = facet.getContentRoots()
                .stream()
                .filter(it -> it instanceof AndroidContentRoot)
                .map(it -> (AndroidContentRoot) it)
                .flatMap(it -> it.getResourceDirectories().stream())
                .collect(Collectors.toList());
        LocalResourceRepository.EmptyRepository dynamicResources =
                new LocalResourceRepository.EmptyRepository(namespace);
        List<LocalResourceRepository> childRepositories = new ArrayList<>(1 + resourceDirectories.size());
        addRepositoriesInReverseOverlayOrder(resourceDirectories, childRepositories, facet);
        childRepositories.add(dynamicResources);
        return new ModuleResourceRepository(facet, namespace, childRepositories, SourceSet.MAIN);
    }

    /**
     * Inserts repositories for the given {@code resourceDirectories} into {@code
     * childRepositories}, in the right order.
     *
     * <p>{@code resourceDirectories} is assumed to be in the order returned from
     * {@link SourceProviderManager#getCurrentSourceProviders()}, which is the inverse of what we
     * need. The code in
     * {@link MultiResourceRepository#getMap(ResourceNamespace, ResourceType, boolean)} gives
     * priority to child repositories which are earlier
     * in the list, so after creating repositories for every folder, we add them in reverse to
     * the list.
     *
     * @param resourceDirectories    directories for which repositories should be constructed
     * @param childRepositories      the list of repositories to which new repositories will be
     *                               added
     * @param facet                  {@link AndroidFacet} that repositories will correspond to
     * @param resourceFolderRegistry {@link ResourceFolderRegistry} used to construct the
     *                               repositories
     */
    private static void addRepositoriesInReverseOverlayOrder(@NotNull List<File> resourceDirectories,
                                                             @NotNull List<LocalResourceRepository> childRepositories,
                                                             @NotNull AndroidModule facet
//                                                             @NotNull ResourceFolderRegistry
//                                                             resourceFolderRegistry
    ) {
        for (int i = resourceDirectories.size(); --i >= 0; ) {
            File resourceDirectory = resourceDirectories.get(i);
            ResourceFolderRepository resourceFolderRepository = ResourceFolderRepository.create(
                    facet,
                    resourceDirectory,
                    ResourceRepositoryManager.getInstance(facet).getNamespace(),
                    null);
            childRepositories.add(resourceFolderRepository);
        }
    }

    private ModuleResourceRepository(@NotNull AndroidModule facet,
                                     @NotNull ResourceNamespace namespace,
                                     @NotNull List<? extends LocalResourceRepository> delegates,
                                     @NotNull SourceSet sourceSet) {
        super(facet.getName());

        myFacet = facet;
        myNamespace = namespace;
        mySourceSet = sourceSet;
//        myRegistry = ResourceFolderRegistry.getInstance(facet.getModule().getProject());

        setChildren(delegates, ImmutableList.of(), ImmutableList.of());
    }

    void updateRoots(List<? extends File> resourceDirectories) {

    }

    @Override
    @NotNull
    public ResourceNamespace getNamespace() {
        return myNamespace;
    }

    @Override
    @Nullable
    public String getPackageName() {
        String packageName = myNamespace.getPackageName();
        return packageName != null
                ? packageName
                : myFacet.getPackageName();
    }

    @NotNull
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .addValue(mySourceSet)
                .toString();
    }
}
