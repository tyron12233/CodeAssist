package com.tyron.code.ui.editor;

import com.tyron.completion.EditorMemory;
import com.tyron.editor.util.EditorUtil;

import org.jetbrains.kotlin.com.intellij.openapi.application.WriteAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.command.WriteCommandAction;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.impl.DocumentCommitThread;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiPackageImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.reference.SoftReference;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.lang.reflect.Field;
import java.util.Map;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.widget.CodeEditor;

public class EditorChangeUtil {
    
    private static final Object fileLock = new Object();

    private static final Field PSI_PACKAGE_CLASS_CACHE_FIELD;

    static {
        try {
            PSI_PACKAGE_CLASS_CACHE_FIELD = PsiPackageImpl.class.getDeclaredField("myClassCache");
            PSI_PACKAGE_CLASS_CACHE_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Synchronizes events from sora editor to intellij's document machinery
     * <p>
     * Normally intellij uses {@link DocumentCommitThread} to perform this synchronization but
     * since we are using a heavily stripped down version of intellij, these classes or the
     * dependencies
     * of this class are not included and does not work properly.
     */
    static void doCommit(CodeEditor editor, int action,
                         int start,
                         int end,
                         CharSequence charSequence,
                         Project project,
                         VirtualFile virtualFile,
                         Document document) {
        WriteCommandAction.runWriteCommandAction(project, "editorChange", null, () -> {
            if (action == ContentChangeEvent.ACTION_DELETE) {
                document.deleteString(start, end);
            } else if (action == ContentChangeEvent.ACTION_INSERT) {
                document.insertString(start, charSequence);
            }
            ProjectCoreUtil.theProject = project;
            FileDocumentManager.getInstance().saveDocument(document);
            PsiDocumentManager.getInstance(project).commitAllDocuments();
        });
    }

    private static void updatePackageCache(Project project, PsiFile psiFile) {
        if (psiFile instanceof PsiJavaFile) {
            PsiJavaFile psiJavaFile = (PsiJavaFile) psiFile;

            String packageName = psiJavaFile.getPackageStatement() ==
                                 null ? "" : psiJavaFile.getPackageStatement().getPackageName();

            PsiPackage aPackage = JavaPsiFacade.getInstance(project).findPackage(packageName);
            if (aPackage != null) {
                try {
                    //noinspection unchecked
                    SoftReference<Map<String, PsiClass[]>> mapSoftReference =
                            (SoftReference<Map<String, PsiClass[]>>) PSI_PACKAGE_CLASS_CACHE_FIELD.get(
                                    aPackage);

                    if (mapSoftReference == null) {
                        PSI_PACKAGE_CLASS_CACHE_FIELD.set(aPackage,
                                mapSoftReference =
                                        new SoftReference<>(ContainerUtil.createConcurrentSoftValueMap()));
                    }

                    Map<String, PsiClass[]> dereference =
                            SoftReference.dereference(mapSoftReference);
                    if (dereference != null) {
                        for (PsiClass aClass : psiJavaFile.getClasses()) {
                            dereference.computeIfPresent(aClass.getName(),
                                    (s, psiClass) -> new PsiClass[]{aClass});
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
