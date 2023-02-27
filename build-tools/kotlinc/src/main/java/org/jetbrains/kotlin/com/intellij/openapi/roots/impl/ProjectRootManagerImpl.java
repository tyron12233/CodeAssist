package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.components.PersistentStateComponent;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ProjectExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerListener;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.util.EventDispatcher;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ProjectRootManagerImpl extends ProjectRootManagerEx implements PersistentStateComponent<Element> {

    private static final Logger LOG = Logger.getInstance(ProjectRootManagerImpl.class);
//    private static final ProjectExtensionPointName<ProjectExtension> EP_NAME = new ProjectExtensionPointName<>("com.intellij.projectExtension");

    private static final String PROJECT_JDK_NAME_ATTR = "project-jdk-name";
    private static final String PROJECT_JDK_TYPE_ATTR = "project-jdk-type";
    private static final String ATTRIBUTE_VERSION = "version";

    protected final Project myProject;

    private final EventDispatcher<ProjectJdkListener> myProjectJdkEventDispatcher = EventDispatcher.create(ProjectJdkListener.class);

    private String myProjectSdkName;
    private String myProjectSdkType;

    private final OrderRootsCache myRootsCache;

    private boolean myStateLoaded;

    private final VirtualFilePointerListener myEmptyRootsValidityChangedListener = new VirtualFilePointerListener(){};

    public static ProjectRootManagerImpl getInstanceImpl(Project project) {
        return (ProjectRootManagerImpl)getInstance(project);
    }

    public ProjectRootManagerImpl(@NotNull Project project) {
        myProject = project;
        myRootsCache = getOrderRootsCache(project);
//        project.getMessageBus().simpleConnect().subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, new ProjectJdkTable.Listener() {
//            @Override
//            public void jdkNameChanged(@NotNull Sdk jdk, @NotNull String previousName) {
//                String currentName = getProjectSdkName();
//                if (previousName.equals(currentName)) {
//                    // if already had jdk name and that name was the name of the jdk just changed
//                    myProjectSdkName = jdk.getName();
//                    myProjectSdkType = jdk.getSdkType().getName();
//                }
//            }
//        });
    }

    @Override
    public @NotNull ProjectFileIndex getFileIndex() {
        return ProjectFileIndex.getInstance(myProject);
    }

    @Override
    public @NotNull List<String> getContentRootUrls() {
        Module[] modules = getModuleManager().getModules();
        List<String> result = new ArrayList<>(modules.length);
        for (Module module : modules) {
            ContainerUtil.addAll(result, ModuleRootManager.getInstance(module).getContentRootUrls());
        }
        return result;
    }

    @Override
    public VirtualFile @NotNull [] getContentRoots() {
        Module[] modules = getModuleManager().getModules();
        List<VirtualFile> result = new ArrayList<>(modules.length);
        for (Module module : modules) {
            VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
            if (modules.length == 1) {
                return contentRoots;
            }

            ContainerUtil.addAll(result, contentRoots);
        }
        return VfsUtilCore.toVirtualFileArray(result);
    }

    @Override
    public VirtualFile @NotNull [] getContentSourceRoots() {
        Module[] modules = getModuleManager().getModules();
        List<VirtualFile> result = new ArrayList<>(modules.length);
        for (Module module : modules) {
            ContainerUtil.addAll(result, ModuleRootManager.getInstance(module).getSourceRoots());
        }
        return VfsUtilCore.toVirtualFileArray(result);
    }

    @Override
    public @NotNull OrderEnumerator orderEntries() {
        return new ProjectOrderEnumerator(myProject, myRootsCache);
    }

    @Override
    public @NotNull OrderEnumerator orderEntries(@NotNull Collection<? extends Module> modules) {
        return new ModulesOrderEnumerator(modules);
    }

    @Override
    public VirtualFile @NotNull [] getContentRootsFromAllModules() {
        Module[] modules = getModuleManager().getSortedModules();
        List<VirtualFile> result = new ArrayList<>(modules.length + 1);
        for (Module module : modules) {
            Collections.addAll(result, ModuleRootManager.getInstance(module).getContentRoots());
        }
        ContainerUtil.addIfNotNull(result, myProject.getBaseDir());
        return VfsUtilCore.toVirtualFileArray(result);
    }

    @Override
    public @Nullable Sdk getProjectSdk() {
        return null;
    }

    @Override
    public @Nullable String getProjectSdkName() {
        return myProjectSdkName;
    }

    @Override
    public @Nullable String getProjectSdkTypeName() {
        return myProjectSdkType;
    }

    @Override
    public void setProjectSdk(@Nullable Sdk sdk) {
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        if (sdk == null) {
            myProjectSdkName = null;
            myProjectSdkType = null;
        }
        else {
            myProjectSdkName = sdk.getName();
            myProjectSdkType = null; //sdk.getSdkType().getName();
        }
        projectJdkChanged();
    }

    public void projectJdkChanged() {
        incModificationCount();
        mergeRootsChangesDuring(getActionToRunWhenProjectJdkChanges());
        fireJdkChanged();
    }

    private void fireJdkChanged() {
        Sdk sdk = getProjectSdk();
//        for (ProjectExtension extension : EP_NAME.getExtensions(myProject)) {
//            extension.projectSdkChanged(sdk);
//        }
    }

    protected @NotNull Runnable getActionToRunWhenProjectJdkChanges() {
        return () -> myProjectJdkEventDispatcher.getMulticaster().projectJdkChanged();
    }

    @Override
    public void setProjectSdkName(@NotNull String name, @NotNull String sdkTypeName) {
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        myProjectSdkName = name;
        myProjectSdkType = sdkTypeName;

        projectJdkChanged();
    }

    @Override
    public void addProjectJdkListener(@NotNull ProjectJdkListener listener) {
        myProjectJdkEventDispatcher.addListener(listener);
    }

    @Override
    public void removeProjectJdkListener(@NotNull ProjectJdkListener listener) {
        myProjectJdkEventDispatcher.removeListener(listener);
    }

    @Override
    public void makeRootsChange(@NotNull Runnable runnable, boolean fileTypes, boolean fireEvents) {

    }

    @Override
    public void markRootsForRefresh() {

    }

    @Override
    public void mergeRootsChangesDuring(@NotNull Runnable runnable) {

    }

    @Override
    public void clearScopesCachesForModules() {
        myRootsCache.clearCache();
        Module[] modules = ModuleManager.getInstance(myProject).getModules();
        for (Module module : modules) {
//            ModuleRootManagerEx.getInstanceEx(module).dropCaches();
        }
    }

    @Override
    public void loadState(@NonNull Element element) {
        myProjectSdkName = element.getAttribute(PROJECT_JDK_NAME_ATTR);
        myProjectSdkType = element.getAttribute(PROJECT_JDK_TYPE_ATTR);

        Application app = ApplicationManager.getApplication();
        if (app != null) {
            Runnable runnable = myStateLoaded ? this::projectJdkChanged :
                    // Prevent root changed event during startup to improve startup performance
                    this::fireJdkChanged;
            app.invokeLater(() -> app.runWriteAction(runnable), ModalityState.NON_MODAL);
        }
        myStateLoaded = true;
    }

    @Override
    public void noStateLoaded() {
        myStateLoaded = true;
    }

    @Override
    public Element getState() {
//        Element element = ;
//        element.setAttribute(ATTRIBUTE_VERSION, "2");
//        for (ProjectExtension extension : EP_NAME.getExtensions(myProject)) {
//            extension.writeExternal(element);
//        }
//        if (myProjectSdkName != null) {
//            element.setAttribute(PROJECT_JDK_NAME_ATTR, myProjectSdkName);
//        }
//        if (myProjectSdkType != null) {
//            element.setAttribute(PROJECT_JDK_TYPE_ATTR, myProjectSdkType);
//        }
//
//        if (element.getAttributes().size() == 1) {
//            // remove empty element to not write defaults
//            element.removeAttribute(ATTRIBUTE_VERSION);
//        }
//        return element;
        return null;
    }

    @Override
    public void initializeComponent() {

    }

    private @NotNull ModuleManager getModuleManager() {
        return ModuleManager.getInstance(myProject);
    }

    @ApiStatus.Internal
    protected OrderRootsCache getOrderRootsCache(@NotNull Project project) {
        return new OrderRootsCache(project);
    }

}
