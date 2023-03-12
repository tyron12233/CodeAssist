package com.tyron.completion.psi.completion;

import androidx.annotation.NonNull;

import com.tyron.completion.InsertHandler;
import com.tyron.completion.InsertionContext;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.psi.codeInsight.ExpectedTypesProvider;
import com.tyron.completion.psi.codeInsight.MethodCallUtils;
import com.tyron.completion.psi.completion.item.JavaPsiClassReferenceElement;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotationMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaClassNameInsertHandler implements InsertHandler<JavaPsiClassReferenceElement> {

    public static final InsertHandler<JavaPsiClassReferenceElement> JAVA_CLASS_INSERT_HANDLER = new JavaClassNameInsertHandler();

    public static boolean isArrayTypeExpected(PsiExpression expr) {
        return ContainerUtil.exists(ExpectedTypesProvider.getExpectedTypes(expr, true),
                info -> {
                    if (info.getType() instanceof PsiArrayType) {
                        PsiMethod method = info.getCalledMethod();
                        return method == null || !method.isVarArgs() || !(expr.getParent() instanceof PsiExpressionList) ||
                               MethodCallUtils.getParameterForArgument(expr) != null;
                    }
                    return false;
                });
    }

    private static boolean insertingAnnotation(InsertionContext context, LookupElement item) {
        final Object obj = item.getObject();
        if (!(obj instanceof PsiClass) || !((PsiClass)obj).isAnnotationType()) return false;

        PsiElement leaf = context.getFile().findElementAt(context.getStartOffset());
        PsiAnnotation anno = PsiTreeUtil.getParentOfType(leaf, PsiAnnotation.class);
        return anno != null && PsiTreeUtil.isAncestor(anno.getNameReferenceElement(), leaf, false);
    }

    static boolean shouldHaveAnnotationParameters(PsiClass annoClass) {
        for (PsiMethod m : annoClass.getMethods()) {
            if (!PsiUtil.isAnnotationMethod(m)) continue;
            if (((PsiAnnotationMethod)m).getDefaultValue() == null) return true;
        }
        return false;
    }

    @Override
    public void handleInsert(@NonNull InsertionContext context,
                             @NonNull JavaPsiClassReferenceElement item) {
        int offset = context.getTailOffset() - 1;
        final PsiFile file = context.getFile();
        PsiImportStatementBase importStatement = PsiTreeUtil.findElementOfClassAtOffset(file, offset, PsiImportStatementBase.class, false);
        if (importStatement != null) {
            PsiJavaCodeReferenceElement ref = findJavaReference(file, offset);
            String qname = item.getQualifiedName();
            if (qname != null && (ref == null || !qname.equals(ref.getCanonicalText()))) {
//                AllClassesGetter.INSERT_FQN.handleInsert(context, item);
            }
            if (importStatement instanceof PsiImportStaticStatement) {
                context.setAddCompletionChar(false);
                context.getDocument().insertString(0, ".");
            }
            return;
        }

        PsiElement position = file.findElementAt(offset);
        PsiJavaCodeReferenceElement ref = position != null && position.getParent() instanceof PsiJavaCodeReferenceElement ?
                (PsiJavaCodeReferenceElement) position.getParent() : null;
        PsiClass psiClass = item.getObject();
        SmartPsiElementPointer<PsiClass> classPointer = SmartPointerManager.createPointer(psiClass);
        final Project project = context.getProject();

        final Editor editor = context.getEditor();
        final char c = context.getCompletionChar();

        throw new UnsupportedOperationException();
    }

    @Nullable
    static PsiJavaCodeReferenceElement findJavaReference(@NotNull PsiFile file, int offset) {
        return PsiTreeUtil.findElementOfClassAtOffset(file, offset, PsiJavaCodeReferenceElement.class, false);
    }

}
