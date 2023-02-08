package com.tyron.completion.psi.completion.item;

import com.tyron.completion.InsertionContext;
import com.tyron.completion.TailType;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.ui.IconManager;

public class PackageLookupItem extends LookupElement {

    private final PsiPackage myPackage;
    private final String myString;
    private final boolean myAddDot;

    public PackageLookupItem(@NotNull PsiPackage aPackage) {
        this(aPackage, null);
    }

    public PackageLookupItem(@NotNull PsiPackage pkg, @Nullable PsiElement context) {
        myPackage = pkg;
        myString = StringUtil.notNullize(myPackage.getName());

        PsiFile file = context == null ? null : context.getContainingFile();
        myAddDot = !(file instanceof PsiJavaCodeReferenceCodeFragment) || ((PsiJavaCodeReferenceCodeFragment)file).isClassesAccepted();
    }

    @NotNull
    @Override
    public Object getObject() {
        return myPackage;
    }

    @NotNull
    @Override
    public String getLookupString() {
        return myString;
    }


    @Override
    public void renderElement(@NotNull LookupElementPresentation presentation) {
        super.renderElement(presentation);
        if (myAddDot) {
            presentation.setItemText(myString + ".");
        }
//        presentation.setIcon(IconManager..getInstance().getPlatformIcon(PlatformIcons.Package));
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context) {
        if (myAddDot) {
            context.setAddCompletionChar(false);
            TailType.DOT.processTail(context.getEditor(), context.getTailOffset());
        }
        if (myAddDot || context.getCompletionChar() == '.') {
//            AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor());
        }
    }
}
