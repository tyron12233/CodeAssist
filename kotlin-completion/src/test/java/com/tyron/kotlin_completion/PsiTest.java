package com.tyron.kotlin_completion;

import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder;
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider;
import org.junit.Before;
import org.junit.Test;

public class PsiTest {

    private final Disposable mDisposable = Disposer.newDisposable();

    private JavaCoreApplicationEnvironment mApplicationEnvironment;
    private JavaCoreProjectEnvironment mProjectEnvironment;
    private Project mProject;

    @Before
    public void setup() {
        mApplicationEnvironment = new JavaCoreApplicationEnvironment(mDisposable);
        mProjectEnvironment = new JavaCoreProjectEnvironment(mDisposable, mApplicationEnvironment);
        mProject = mProjectEnvironment.getProject();

        mProjectEnvironment.registerProjectExtensionPoint(PsiElementFinder.EP_NAME, PsiElementFinder.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME,
                PsiAugmentProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(JavaModuleSystem.EP_NAME,
                JavaModuleSystem.class);
    }

    @Test
    public void test() {
//        PsiFileFactory factory = PsiFileFactory.getInstance(mProject);
//        String text = "public class Main { Main main; void main() { main.main(); }}";
//        String text2 = "class Main2 { Main main; }";
//        PsiFile file =
//                factory.createFileFromText(JavaLanguage.INSTANCE, text);
//        PsiFile file2 =
//                factory.createFileFromText(JavaLanguage.INSTANCE, text2);
//        PsiElement element = file2.findElementAt(23);
//        System.out.println(element.getContext().getReference());
//
//        UtilKt.setIdeaIoUseFallback();
//        mProjectEnvironment.addJarToClassPath(new File("/home/tyron/AndroidStudioProjects/CodeAssist/build-logic/src/test/resources/bootstraps/rt.jar"));
//        PsiClass aClass = JavaFileManager.getInstance(mProject)
//                .findClass("java.lang.String", GlobalSearchScope.allScope(mProject));
//        System.out.println(aClass);
    }
}
