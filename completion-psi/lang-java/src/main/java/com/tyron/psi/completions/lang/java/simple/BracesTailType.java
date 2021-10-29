package com.tyron.psi.completions.lang.java.simple;

import com.tyron.psi.editor.Editor;
import com.tyron.psi.tailtype.TailType;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.kotlin.com.intellij.util.text.CharArrayUtil;

/**
 * @author peter
 */
public class BracesTailType extends TailType {

    @Override
    public int processTail(final Editor editor, int tailOffset) {
        int startOffset = tailOffset;

        CharSequence seq = editor.getDocument().getCharsSequence();
        int nextNonWs = CharArrayUtil.shiftForward(seq, tailOffset, " \t");
        if (nextNonWs < seq.length() && seq.charAt(nextNonWs) == '{') {
            tailOffset = nextNonWs + 1;
        } else {
            tailOffset = insertChar(editor, startOffset, '{');
        }

        tailOffset = reformatBrace(editor, tailOffset, startOffset);

//        if (EnterAfterUnmatchedBraceHandler.isAfterUnmatchedLBrace(editor, tailOffset, getFileType(editor))) {
//            new EnterHandler(EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER))
//                    .executeWriteAction(editor, DataManager.getInstance().getDataContext(editor.getContentComponent()));
//            return editor.getCaretModel().getOffset();
//        }
        return tailOffset;
    }

    private static int reformatBrace(Editor editor, int tailOffset, int startOffset) {
        Project project = editor.getProject();
        if (project != null) {
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (psiFile != null) {
                editor.getCaretModel().moveToOffset(tailOffset);
                CodeStyleManager.getInstance(project).reformatText(psiFile, startOffset, tailOffset);
                tailOffset = editor.getCaretModel().getOffset();
            }
        }
        return tailOffset;
    }
}