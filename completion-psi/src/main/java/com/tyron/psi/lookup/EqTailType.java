package com.tyron.psi.lookup;

import com.tyron.psi.editor.Editor;
import com.tyron.psi.tailtype.TailType;
import com.tyron.psi.util.DocumentUtils;
import com.tyron.psi.util.PsiEditorUtil;

import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

public class EqTailType extends TailType {
    public static final TailType INSTANCE = new EqTailType();

    protected boolean isSpaceAroundAssignmentOperators(Editor editor, int tailOffset) {
        PsiFile psiFile = PsiEditorUtil.getPsiFile(editor);
        //Language language = PsiUtilCore.getLanguageAtOffset(psiFile, tailOffset);
      //  CodeStyleSettingsFacade codeStyleFacade = CodeStyle.getFacade(psiFile).withLanguage(language);
        //return codeStyleFacade.isSpaceAroundAssignmentOperators();
        return true;
    }

    @Override
    public int processTail(final Editor editor, int tailOffset) {
        Document document = editor.getDocument();
        int textLength = document.getTextLength();
        CharSequence chars = document.getCharsSequence();
        if (tailOffset < textLength - 1 && chars.charAt(tailOffset) == ' ' && chars.charAt(tailOffset + 1) == '=') {
            return moveCaret(editor, tailOffset, 2);
        }
        if (tailOffset < textLength && chars.charAt(tailOffset) == '=') {
            return moveCaret(editor, tailOffset, 1);
        }
        if (isSpaceAroundAssignmentOperators(editor, tailOffset)) {
            DocumentUtils.insertString(document, tailOffset, " =");
            tailOffset = moveCaret(editor, tailOffset, 2);
            tailOffset = insertChar(editor, tailOffset, ' ');
        }
        else {
            DocumentUtils.insertString(document, tailOffset, "=");
            tailOffset = moveCaret(editor, tailOffset, 1);
        }
        return tailOffset;
    }
}
