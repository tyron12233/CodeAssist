package com.tyron.completion.lookup.impl;

import com.tyron.completion.TailType;
import com.tyron.editor.Editor;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

import io.github.rosemoe.sora.widget.CodeEditor;

public class EqTailType extends TailType {
  public static final TailType INSTANCE = new EqTailType();

  protected boolean isSpaceAroundAssignmentOperators(Editor editor, int tailOffset) {
//    PsiFile psiFile = PsiEditorUtil.getPsiFile(editor);
//    Language language = PsiUtilCore.getLanguageAtOffset(psiFile, tailOffset);
//    CodeStyleSettingsFacade codeStyleFacade = CodeStyle.getFacade(psiFile).withLanguage(language);
//    return codeStyleFacade.isSpaceAroundAssignmentOperators();
    return true;
  }

  @Override
  public int processTail(final Editor editor, int tailOffset) {
    Document document = editor.getDocument();
    int textLength = document.getTextLength();
    if (tailOffset < textLength - 1 && ((CharSequence) document).charAt(tailOffset) == ' ' && ((CharSequence) document).charAt(tailOffset + 1) == '=') {
      return moveCaret(editor, tailOffset, 2);
    }
    if (tailOffset < textLength && ((CharSequence) document).charAt(tailOffset) == '=') {
      return moveCaret(editor, tailOffset, 1);
    }
    if (isSpaceAroundAssignmentOperators(editor, tailOffset)) {
      document.insertString(tailOffset, "=");
      tailOffset = moveCaret(editor, tailOffset, 2);
      tailOffset = insertChar(editor, tailOffset, ' ');
    }
    else {
      document.insertString(tailOffset, "=");
      tailOffset = moveCaret(editor, tailOffset, 1);
    }
    return tailOffset;
  }
}