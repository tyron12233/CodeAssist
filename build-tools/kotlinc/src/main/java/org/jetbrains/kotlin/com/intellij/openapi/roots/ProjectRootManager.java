package org.jetbrains.kotlin.com.intellij.openapi.roots;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.SimpleModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;

import java.util.Collection;
import java.util.List;

@ApiStatus.NonExtendable
public abstract class ProjectRootManager extends SimpleModificationTracker {

    /**
     * Returns the project root manager instance for the specified project.
     *
     * @param project the project for which the instance is requested.
     * @return the instance.
     */
    public static ProjectRootManager getInstance(@NotNull Project project) {
        return project.getService(ProjectRootManager.class);
    }

    /**
     * Returns the file index for the project.
     *
     * @return the file index instance.
     */
    @NotNull
    public abstract ProjectFileIndex getFileIndex();

    /**
     * Creates new enumerator instance to process dependencies of all modules in the project. Only first level dependencies of
     * modules are processed so {@link OrderEnumerator#recursively()} option is ignored and {@link OrderEnumerator#withoutDepModules()} option is forced
     * @return new enumerator instance
     */
    @NotNull
    public abstract OrderEnumerator orderEntries();

    /**
     * Creates new enumerator instance to process dependencies of several modules in the project. Caching is not supported for this enumerator
     * @param modules modules to process
     * @return new enumerator instance
     */
    @NotNull
    public abstract OrderEnumerator orderEntries(@NotNull Collection<? extends Module> modules);

    /**
     * Unlike getContentRoots(), this includes the project base dir. Is this really necessary?
     * TODO: remove this method?
     */
    public abstract VirtualFile @NotNull [] getContentRootsFromAllModules();

    /**
     * Returns the list of content root URLs for all modules in the project.
     *
     * @return the list of content root URLs.
     */
    @NotNull
    public abstract List<String> getContentRootUrls();

    /**
     * Returns the list of content roots for all modules in the project.
     *
     * @return the array of content roots.
     */
    public abstract VirtualFile @NotNull [] getContentRoots();

    /**
     * Returns the list of source roots under the content roots for all modules in the project.
     *
     * @return the array of content source roots.
     */
    public abstract VirtualFile @NotNull [] getContentSourceRoots();

//    /**
//     * Returns the list of source roots from all modules which types belong to the specified set
//     *
//     * @param rootTypes types of source roots
//     * @return list of source roots
//     */
//    @NotNull
//    public abstract List<VirtualFile> getModuleSourceRoots(@NotNull Set<? extends JpsModuleSourceRootType<?>> rootTypes);

    /**
     * @return the instance of the JDK selected for the project or null
     * if the name of the selected JDK does not correspond to any existing JDK instance
     */
    @Nullable
    public abstract Sdk getProjectSdk();

    /**
     * @return the name of the SDK selected for the project
     */
    @Nullable
    public abstract String getProjectSdkName();

    /**
     * @return the SDK type name (@link {@link SdkTypeId#getName()} of the current Project SDK
     */
    @Nullable
    public abstract String getProjectSdkTypeName();

    /**
     * Sets the SDK to be used for the project.
     *
     * @param sdk the SDK instance.
     */
    public abstract void setProjectSdk(@Nullable Sdk sdk);

    /**
     * Sets the name of the JDK to be used for the project
     * @param sdkTypeName the {@link SdkTypeId#getName()} of the SDK type
     */
    public abstract void setProjectSdkName(@NotNull String name, @NotNull String sdkTypeName);
}
