package com;

import com.tyron.builder.TestProject;

import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.DefaultLogger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionsArea;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.SystemInfoRt;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.augment.AugmentProvider;
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

public class PsiTest {

    JavaCoreProjectEnvironment mProjectEnvironment;
    VirtualFileSystem mFileSystem;

    @Before
    public void setup() {
        System.setProperty("idea.home.path", "C:/Program Files/Android/Android Studio");
        Disposable disposable = Disposer.newDisposable();
        JavaCoreApplicationEnvironment applicationEnvironment = new JavaCoreApplicationEnvironment(disposable);
        mProjectEnvironment = new JavaCoreProjectEnvironment(disposable, applicationEnvironment);
        mProjectEnvironment.registerProjectExtensionPoint(PsiElementFinder.EP_NAME, PsiElementFinder.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME, AugmentProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(JavaModuleSystem.EP_NAME, JavaModuleSystem.class);
        mFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);

        mProjectEnvironment.addJarToClassPath(new File(TestProject.resolveBasePath() + "/TestProject/app/libs/rt.jar"));
        mProjectEnvironment.addSourcesToClasspath(Objects.requireNonNull(mFileSystem.findFileByPath(
                TestProject.resolveBasePath() + "/TestProject/app/src/main/java")));
    }


    @Test
    public void test() {
        PsiManager manager = PsiManager.getInstance(mProjectEnvironment.getProject());
        PsiFile file = manager.findFile(Objects.requireNonNull(mFileSystem.findFileByPath(
                TestProject.resolveBasePath() + "/TestProject/app/src/main/java/com/tyron/test/MainActivity.java")));
        PsiJavaFile javaFile = (PsiJavaFile) file;
        PsiElement element = javaFile.findElementAt(126);
        PsiIdentifier identifier = (PsiIdentifier) element;
        PsiReferenceExpression expression = (PsiReferenceExpression) identifier.getContext();
    }
}
