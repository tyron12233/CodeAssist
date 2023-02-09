package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.InsertionContext;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;
import com.tyron.completion.psi.completion.TypedLookupItem;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

import java.util.Objects;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaMethodReferenceElement extends LookupElement implements TypedLookupItem {
    private final PsiMethod myMethod;
    private final PsiElement myRefPlace;
    private final PsiType myType;

    public JavaMethodReferenceElement(PsiMethod method, PsiElement refPlace, @Nullable PsiType type) {
        myMethod = method;
        myRefPlace = refPlace;
        myType = type;
    }

    @Override
    public @Nullable PsiType getType() {
        return myType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof JavaMethodReferenceElement) {
            JavaMethodReferenceElement element = (JavaMethodReferenceElement) o;
            if (getLookupString().equals(element.getLookupString())) {
                return myRefPlace.equals(element.myRefPlace);
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getLookupString(), myRefPlace);
    }

    @NotNull
    @Override
    public Object getObject() {
        return myMethod;
    }

    @NotNull
    @Override
    public String getLookupString() {
        return myMethod.isConstructor() ? "new" : myMethod.getName();
    }

    @Override
    public void renderElement(@NotNull LookupElementPresentation presentation) {
//        presentation.setIcon(myMethod.getIcon(Iconable.ICON_FLAG_VISIBILITY));
        super.renderElement(presentation);
    }

    @Override
    public void handleInsert(InsertionContext context) {
        if (!(myRefPlace instanceof PsiMethodReferenceExpression)) {
            PsiClass containingClass = Objects.requireNonNull(myMethod.getContainingClass());
            String qualifiedName = Objects.requireNonNull(containingClass.getQualifiedName());

            CodeEditor editor = context.getEditor();
            int startOffset = context.getStartOffset();
            CharPosition position = editor.getText().getIndexer().getCharPosition(startOffset);


            editor.getText().insert(position.line, position.column, qualifiedName + "::");
        }
    }
}
