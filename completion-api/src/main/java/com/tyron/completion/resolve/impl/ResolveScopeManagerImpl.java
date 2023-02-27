package com.tyron.completion.resolve.impl;

import static org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl.ANY_PSI_CHANGE_TOPIC;

import android.annotation.SuppressLint;

import com.tyron.completion.resolve.ResolveScopeEnlarger;
import com.tyron.completion.resolve.ResolveScopeProvider;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.injected.editor.VirtualFileWindow;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.roots.TestSourcesFilter;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.LibraryScopeCache;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.FileResolveScopeProvider;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiDirectory;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.AnyPsiChangeListener;
import org.jetbrains.kotlin.com.intellij.psi.impl.ResolveScopeManager;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.PsiSearchScopeUtil;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.VfsUtil;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentFactoryMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.AdditionalIndexableFileSet;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ResolveScopeManagerImpl extends ResolveScopeManager implements Disposable {

    private final Project myProject;
        private final ProjectRootManager myProjectRootManager;
    private final PsiManager myManager;

    private final Map<VirtualFile, GlobalSearchScope> myDefaultResolveScopesCache;
    private final AdditionalIndexableFileSet myAdditionalIndexableFileSet;

    public ResolveScopeManagerImpl(Project project) {
        myProject = project;
        myProjectRootManager = ProjectRootManager.getInstance(project);
        myManager = PsiManager.getInstance(project);
        myAdditionalIndexableFileSet = new AdditionalIndexableFileSet(project);

        myDefaultResolveScopesCache =
                ConcurrentFactoryMap.create(key -> ReadAction.compute(() -> createScopeByFile(key)),
                        ContainerUtil::createConcurrentWeakKeySoftValueMap);

        myProject.getMessageBus()
                .connect(this)
                .subscribe(ANY_PSI_CHANGE_TOPIC, new AnyPsiChangeListener() {
                    @Override
                    public void beforePsiChanged(boolean isPhysical) {
                        if (isPhysical) {
                            myDefaultResolveScopesCache.clear();
                        }
                    }
                });

        // Make it explicit that registering and removing ResolveScopeProviders needs to clear
        // the resolve scope cache
        // (even though normally registerRunnableToRunOnChange would be enough to clear the cache)
        ResolveScopeProvider.EP_NAME.addChangeListener(myDefaultResolveScopesCache::clear, this);
        ResolveScopeEnlarger.EP_NAME.addChangeListener(myDefaultResolveScopesCache::clear, this);
    }

    @NotNull
    private GlobalSearchScope createScopeByFile(@NotNull VirtualFile key) {
        VirtualFile file = key;
        VirtualFile original =
                key instanceof LightVirtualFile ? ((LightVirtualFile) key).getOriginalFile() : null;
        if (original != null) {
            file = original;
        }
        GlobalSearchScope scope = null;
        for (ResolveScopeProvider resolveScopeProvider :
                ResolveScopeProvider.EP_NAME.getExtensionList()) {
            scope = resolveScopeProvider.getResolveScope(file, myProject);
            if (scope != null) {
                break;
            }
        }
        if (scope == null) {
            scope = getInherentResolveScope(file);
        }
        for (ResolveScopeEnlarger enlarger : ResolveScopeEnlarger.EP_NAME.getExtensions()) {
            SearchScope extra = enlarger.getAdditionalResolveScope(file, myProject);
            if (extra != null) {
                scope = scope.union(extra);
            }
        }
        if (original != null && !scope.contains(key)) {
            scope = scope.union(GlobalSearchScope.fileScope(myProject, key));
        }
        return scope;
    }

    @NotNull
    private GlobalSearchScope getResolveScopeFromProviders(@NotNull VirtualFile vFile) {
        return Objects.requireNonNull(myDefaultResolveScopesCache.get(vFile));
    }

    @SuppressLint("NewApi")
    @NotNull
    private GlobalSearchScope getInherentResolveScope(@NotNull VirtualFile vFile) {
        ProjectFileIndex projectFileIndex = myProjectRootManager.getFileIndex();
        Module module = projectFileIndex.getModuleForFile(vFile);
        if (module != null) {
            boolean includeTests = TestSourcesFilter.isTestSources(vFile, myProject);
            return GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, includeTests);
        }

        if (!projectFileIndex.isInLibrary(vFile)) {
            GlobalSearchScope allScope = GlobalSearchScope.allScope(myProject);
            if (!allScope.contains(vFile)) {
                return GlobalSearchScope.fileScope(myProject, vFile).uniteWith(allScope);
            }
            return allScope;
        }

        return LibraryScopeCache.getInstance(myProject).getLibraryScope(projectFileIndex.getOrderEntriesForFile(vFile));
    }

    @Override
    public void dispose() {

    }

    @Override
    public @NotNull GlobalSearchScope getResolveScope(@NotNull PsiElement element) {
        ProgressIndicatorProvider.checkCanceled();

        if (element instanceof PsiDirectory) {
            return getResolveScopeFromProviders(((PsiDirectory) element).getVirtualFile());
        }

        PsiFile containingFile = element.getContainingFile();
        if (containingFile instanceof PsiCodeFragment) {
            GlobalSearchScope forcedScope =
                    ((PsiCodeFragment) containingFile).getForcedResolveScope();
            if (forcedScope != null) {
                return forcedScope;
            }
        }

        if (containingFile != null) {
            PsiElement context = containingFile.getContext();
            if (context != null) {
                return withFile(containingFile, getResolveScope(context));
            }
        }

        if (containingFile == null) {
            return GlobalSearchScope.allScope(myProject);
        }
        //        ModelBranch branch = ModelBranch.getPsiBranch(containingFile);
//        return branch != null ? ((ModelBranchImpl)branch).modifyScope(scope) : scope;
        return getPsiFileResolveScope(containingFile);
    }

    @NotNull
    private GlobalSearchScope getPsiFileResolveScope(@NotNull PsiFile psiFile) {
        if (psiFile instanceof FileResolveScopeProvider) {
            return ((FileResolveScopeProvider) psiFile).getFileResolveScope();
        }
        if (!psiFile.getOriginalFile().isPhysical() && !psiFile.getViewProvider().isPhysical()) {
            return withFile(psiFile, GlobalSearchScope.allScope(myProject));
        }
        return getResolveScopeFromProviders(psiFile.getViewProvider().getVirtualFile());
    }

    private GlobalSearchScope withFile(PsiFile containingFile, GlobalSearchScope scope) {
        return PsiSearchScopeUtil.isInScope(scope, containingFile) ? scope : scope.uniteWith(
                GlobalSearchScope.fileScope(myProject,
                        containingFile.getViewProvider().getVirtualFile()));
    }

    @Override
    public @NotNull GlobalSearchScope getDefaultResolveScope(@NotNull VirtualFile vFile) {
        PsiFile psiFile = myManager.findFile(vFile);
        assert psiFile != null : "directory=" +
                                 vFile.isDirectory() +
                                 "; " +
                                 myProject +
                                 "; vFile=" +
                                 vFile +
                                 "; type=" +
                                 vFile.getFileType();
        return getResolveScopeFromProviders(vFile);
    }

    @Override
    public @NotNull GlobalSearchScope getUseScope(@NotNull PsiElement element) {
        VirtualFile vDirectory;
        VirtualFile virtualFile;
        PsiFile containingFile;
        GlobalSearchScope allScope = GlobalSearchScope.allScope(myManager.getProject());
        if (element instanceof PsiDirectory) {
            vDirectory = ((PsiDirectory) element).getVirtualFile();
            virtualFile = null;
            containingFile = null;
        } else {
            containingFile = element.getContainingFile();
            if (containingFile == null) {
                return allScope;
            }
            virtualFile = containingFile.getVirtualFile();
            if (virtualFile == null) {
                return allScope;
            }
            if (virtualFile instanceof VirtualFileWindow) {
                return GlobalSearchScope.fileScope(myProject,
                        ((VirtualFileWindow) virtualFile).getDelegate());
            }
//            if (ScratchUtil.isScratch(virtualFile)) {
//                return GlobalSearchScope.fileScope(myProject, virtualFile);
//            }
            vDirectory = virtualFile.getParent();
        }

        if (vDirectory == null) {
            return allScope;
        }
        ProjectFileIndex projectFileIndex = myProjectRootManager.getFileIndex();
        VirtualFile notNullVFile = virtualFile != null ? virtualFile : vDirectory;
        Module module = VfsUtil.findModuleForFile(myProject, notNullVFile);
        if (module == null) {
            List<OrderEntry> entries = projectFileIndex.getOrderEntriesForFile(notNullVFile);
            if (entries.isEmpty() && (projectFileIndex.isInLibrary(notNullVFile) ||
            myAdditionalIndexableFileSet.isInSet(notNullVFile))) {
                return allScope;
            }

            GlobalSearchScope result = LibraryScopeCache.getInstance(myProject)
            .getLibraryUseScope(entries);
            return containingFile == null || virtualFile.isDirectory() || result.contains
            (virtualFile)
                    ? result : GlobalSearchScope.fileScope(containingFile).uniteWith(result);
        }
        boolean isTest = false;
        return isTest
                ? GlobalSearchScope.moduleTestsWithDependentsScope(module)
                : GlobalSearchScope.moduleWithDependentsScope(module);
    }
}
