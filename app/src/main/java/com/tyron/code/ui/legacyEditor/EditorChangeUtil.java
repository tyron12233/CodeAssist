package com.tyron.code.ui.legacyEditor;

import com.tyron.completion.CompletionInitializationContext;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProcess;
import com.tyron.completion.CompletionService;
import com.tyron.completion.CompletionType;
import com.tyron.completion.EditorMemory;
import com.tyron.completion.impl.CompletionInitializationUtil;
import com.tyron.completion.impl.OffsetsInFile;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.model.CompletionItemWithMatchLevel;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.command.WriteCommandAction;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.impl.DocumentCommitThread;
import org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiPackageImpl;
import org.jetbrains.kotlin.com.intellij.reference.SoftReference;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Supplier;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
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
    public static void doCommit(int action,
                                int start,
                                int end,
                                CharSequence charSequence,
                                Project project,
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

    public static void performCompletionUnderIndicator(
            Project project,
            Editor editor,
            CompletionPublisher publisher,
            Disposable completionSession) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());

        publisher.setComparator((o1, o2) -> {
            if (o1 instanceof CompletionItemWithMatchLevel &&
                o2 instanceof CompletionItemWithMatchLevel) {
                return CompletionList.COMPARATOR.compare((CompletionItemWithMatchLevel) o1,
                        (CompletionItemWithMatchLevel) o2);
            }
            return 0;
        });


        CompletionInitializationContext ctx =
                CompletionInitializationUtil.createCompletionInitializationContext(project,
                        editor,
                        editor.getCaret(),
                        0,
                        CompletionType.SMART);
        CompletionProcess completionProcess = () -> true;

        PsiFile psiFile = editor.getUserData(EditorMemory.FILE_KEY);
        OffsetsInFile offsetsInFile = new OffsetsInFile(psiFile, ctx.getOffsetMap());

        Supplier<? extends OffsetsInFile> supplier =
                CompletionInitializationUtil.insertDummyIdentifier(ctx,
                        offsetsInFile,
                        completionSession);
        OffsetsInFile newOffsets = supplier.get();

        CompletionParameters completionParameters =
                CompletionInitializationUtil.createCompletionParameters(ctx,
                        completionProcess,
                        newOffsets);

        CompletionService.getCompletionService()
                .performCompletion(completionParameters, completionResult -> {
                    LookupElement lookupElement = completionResult.getLookupElement();
                    if (lookupElement.isValid()) {
                        publisher.addItem(new CodeAssistCompletionAdapter.LookupElementWrapper(lookupElement));

                        lookupElement.putUserData(LookupElement.PREFIX_MATCHER_KEY,
                                completionResult.getPrefixMatcher());
                    }
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
