package com.tyron.code.project;

import com.tyron.code.module.FileIndexFacadeImpl;
import com.tyron.code.module.ModuleManagerImpl;
import com.tyron.code.sdk.SdkManagerImpl;
import com.tyron.code.ui.legacyEditor.DummyCodeStyleManager;
import com.tyron.completion.psi.search.PsiShortNamesCache;
import com.tyron.completion.resolve.impl.ResolveScopeManagerImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.jvm.compiler.MockExternalAnnotationsManager;
import org.jetbrains.kotlin.cli.jvm.compiler.MockInferredAnnotationsManager;
import org.jetbrains.kotlin.com.intellij.codeInsight.ExternalAnnotationsManager;
import org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationsManager;
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.CodeAssistProject;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.roots.PackageIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.DirectoryIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.DirectoryIndexImpl;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.LibraryScopeCache;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ProjectFileIndexImpl;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ProjectRootManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.pom.MockPomModel;
import org.jetbrains.kotlin.com.intellij.pom.PomModel;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PackageIndexImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor;
import org.jetbrains.kotlin.com.intellij.psi.impl.ResolveScopeManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.JavaFileManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableFilesIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesIndexImpl;
import org.jetbrains.kotlin.org.picocontainer.PicoContainer;

public class CodeAssistJavaCoreProjectEnvironment extends JavaCoreProjectEnvironment {

    public CodeAssistJavaCoreProjectEnvironment(@NotNull Disposable parentDisposable,
                                                @NotNull CoreApplicationEnvironment applicationEnvironment,
                                                VirtualFile projectPath) {
        super(parentDisposable, applicationEnvironment);
        ((CodeAssistProject) myProject).setProjectRoot(projectPath);


        preregisterExtensionPoints();
        registerExtensions();
        registerServices();
    }

    @Override
    protected @NotNull CodeAssistProject createProject(@NotNull PicoContainer parent,
                                                 @NotNull Disposable parentDisposable) {
        return new CodeAssistProject(parent, parentDisposable);
    }

    @Override
    public @NotNull CodeAssistProject getProject() {
        return (CodeAssistProject) super.getProject();
    }

    @Override
    protected @NotNull ResolveScopeManager createResolveScopeManager(@NotNull PsiManager psiManager) {
        myProject.registerService(ProjectRootManager.class, new ProjectRootManagerImpl(myProject));
        return new ResolveScopeManagerImpl(myProject);
    }

    @Override
    protected JavaFileManager createCoreFileManager() {
        return new JavaFileManagerImpl(PsiManager.getInstance(myProject));
    }

    @Override
    protected PackageIndex createCorePackageIndex() {
        return new PackageIndexImpl(myProject);
    }

    @Override
    protected @NotNull FileIndexFacade createFileIndexFacade() {
        myProject.registerService(DirectoryIndex.class, new DirectoryIndexImpl(myProject));
        myProject.registerService(ProjectFileIndex.class, new ProjectFileIndexImpl(myProject));
        return new FileIndexFacadeImpl(myProject);
    }

    @Override
    public void addSourcesToClasspath(@NotNull VirtualFile root) {
        // do nothing
    }

    protected SdkManager createSdkManager() {
        return new SdkManagerImpl(myProject);
    }

    @SuppressWarnings("deprecation")
    protected void preregisterExtensionPoints() {
        getProject().getExtensionArea().registerExtensionPoint(
                PsiTreeChangeListener.EP.getName(),
                PsiTreeChangeListener.class.getName(),
                ExtensionPoint.Kind.INTERFACE
        );
        registerProjectExtensionPoint(PsiShortNamesCache.EP_NAME, PsiShortNamesCache.class);
        registerProjectExtensionPoint(PsiTreeChangePreprocessor.EP_NAME, PsiTreeChangePreprocessor.class);
        registerProjectExtensionPoint(JvmElementProvider.EP_NAME, JvmElementProvider.class);
        registerProjectExtensionPoint(PsiElementFinder.EP_NAME, PsiElementFinder.class);
    }

    protected void registerExtensions() {
        PsiElementFinder.EP.getPoint(myProject).registerExtension(
                new PsiElementFinderImpl(myProject),
                myProject
        );
    }


    protected void registerServices() {
        myProject.registerService(IndexableFilesIndex.class, new IndexableFilesIndexImpl(myProject));
        myProject.registerService(LibraryScopeCache.class, new LibraryScopeCache(myProject));
        myProject.registerService(SdkManager.class, new SdkManagerImpl(myProject));
        myProject.registerService(ModuleManager.class, new ModuleManagerImpl(myProject));
        myProject.registerService(PsiShortNamesCache.class, new PsiShortNamesCache() {
            @Override
            public @NotNull PsiClass @NotNull [] getClassesByName(@NotNull String name,
                                                                  @NotNull GlobalSearchScope scope) {
                return new PsiClass[0];
            }

            @Override
            public @NotNull String @NotNull [] getAllClassNames() {
                return new String[0];
            }

            @Override
            public @NotNull PsiMethod @NotNull [] getMethodsByName(@NotNull String name,
                                                                   @NotNull GlobalSearchScope scope) {
                return new PsiMethod[0];
            }

            @Override
            public @NotNull PsiMethod @NotNull [] getMethodsByNameIfNotMoreThan(@NotNull String name,
                                                                                @NotNull GlobalSearchScope scope,
                                                                                int maxCount) {
                return new PsiMethod[0];
            }

            @Override
            public @NotNull PsiField @NotNull [] getFieldsByNameIfNotMoreThan(@NotNull String name,
                                                                              @NotNull GlobalSearchScope scope,
                                                                              int maxCount) {
                return new PsiField[0];
            }

            @Override
            public boolean processMethodsWithName(@NotNull String name,
                                                  @NotNull GlobalSearchScope scope,
                                                  @NotNull Processor<? super PsiMethod> processor) {
                return false;
            }

            @Override
            public @NotNull String @NotNull [] getAllMethodNames() {
                return new String[0];
            }

            @Override
            public @NotNull PsiField @NotNull [] getFieldsByName(@NotNull String name,
                                                                 @NotNull GlobalSearchScope scope) {
                return new PsiField[0];
            }

            @Override
            public @NotNull String @NotNull [] getAllFieldNames() {
                return new String[0];
            }
        });
        myProject.registerService(PomModel.class, new MockPomModel(myProject));
        myProject.registerService(ExternalAnnotationsManager.class, new MockExternalAnnotationsManager());
        myProject.registerService(InferredAnnotationsManager.class, new MockInferredAnnotationsManager());
        myProject.registerService(BlockSupport.class, new BlockSupportImpl());
        myProject.registerService(PsiNameHelper.class, new PsiNameHelperImpl(myProject));
        myProject.registerService(CodeStyleManager.class, new DummyCodeStyleManager(myProject));

        PsiManagerImpl psiManager = (PsiManagerImpl) PsiManager.getInstance(myProject);
        NotNullLazyValue<FileIndexFacade> fileIndexProvider =
                NotNullLazyValue.createValue(() -> FileIndexFacade.getInstance(myProject));
        myProject.registerService(FileManager.class, new FileManagerImpl(psiManager, fileIndexProvider));
    }
}
