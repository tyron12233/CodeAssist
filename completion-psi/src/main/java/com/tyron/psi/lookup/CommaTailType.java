package com.tyron.psi.lookup;

import com.tyron.psi.editor.Editor;
import com.tyron.psi.tailtype.TailType;
import com.tyron.psi.util.PsiEditorUtil;

import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

public class CommaTailType extends TailType {
    public static final TailType INSTANCE = new CommaTailType();

    @Override
    public int processTail(final Editor editor, int tailOffset) {
        PsiFile psiFile = PsiEditorUtil.getPsiFile(editor);
        Language language = PsiUtilCore.getLanguageAtOffset(PsiEditorUtil.getPsiFile(editor), tailOffset);
       // CodeStyleSettingsFacade codeStyleFacade = CodeStyle.getFacade(psiFile).withLanguage(language);
       // if (codeStyleFacade.isSpaceBeforeComma()) tailOffset = insertChar(editor, tailOffset, ' ');
        tailOffset = insertChar(editor, tailOffset, ',');
        tailOffset = insertChar(editor, tailOffset, ' ');
        return tailOffset;
    }

    public String toString() {
        return "COMMA";
    }
}
