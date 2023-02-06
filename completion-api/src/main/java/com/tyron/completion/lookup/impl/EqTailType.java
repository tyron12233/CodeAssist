package com.tyron.completion.lookup.impl;

import com.tyron.completion.TailType;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

public class EqTailType extends TailType {
  public static final TailType INSTANCE = new EqTailType();

  protected boolean isSpaceAroundAssignmentOperators(CodeEditor editor, int tailOffset) {
//    PsiFile psiFile = PsiEditorUtil.getPsiFile(editor);
//    Language language = PsiUtilCore.getLanguageAtOffset(psiFile, tailOffset);
//    CodeStyleSettingsFacade codeStyleFacade = CodeStyle.getFacade(psiFile).withLanguage(language);
//    return codeStyleFacade.isSpaceAroundAssignmentOperators();
    return true;
  }

  @Override
  public int processTail(final CodeEditor editor, int tailOffset) {
    Content document = editor.getText();
    int textLength = document.length();
    if (tailOffset < textLength - 1 && ((CharSequence) document).charAt(tailOffset) == ' ' && ((CharSequence) document).charAt(tailOffset + 1) == '=') {
      return moveCaret(editor, tailOffset, 2);
    }
    if (tailOffset < textLength && ((CharSequence) document).charAt(tailOffset) == '=') {
      return moveCaret(editor, tailOffset, 1);
    }
    if (isSpaceAroundAssignmentOperators(editor, tailOffset)) {
      editor.insertText(" =", tailOffset);
      tailOffset = moveCaret(editor, tailOffset, 2);
      tailOffset = insertChar(editor, tailOffset, ' ');
    }
    else {
      editor.insertText("=", tailOffset);
      tailOffset = moveCaret(editor, tailOffset, 1);
    }
    return tailOffset;
  }
}