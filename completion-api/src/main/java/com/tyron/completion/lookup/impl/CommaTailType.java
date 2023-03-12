package com.tyron.completion.lookup.impl;

import com.tyron.completion.TailType;
import com.tyron.editor.Editor;
import com.tyron.language.api.Language;

import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

import io.github.rosemoe.sora.widget.CodeEditor;

public class CommaTailType extends TailType {
  public static final TailType INSTANCE = new CommaTailType();

  @Override
  public int processTail(final Editor editor, int tailOffset) {
//    PsiFile psiFile = PsiEditorUtil.getPsiFile(editor);
//    Language language = PsiUtilCore.getLanguageAtOffset(PsiEditorUtil.getPsiFile(editor), tailOffset);
//    CodeStyleSettingsFacade codeStyleFacade = CodeStyle.getFacade(psiFile).withLanguage(language);
//      if (codeStyleFacade.isSpaceBeforeComma()) {
          tailOffset = insertChar(editor, tailOffset, ' ');
//      }
    tailOffset = insertChar(editor, tailOffset, ',');
//      if (codeStyleFacade.isSpaceAfterComma()) {
          tailOffset = insertChar(editor, tailOffset, ' ');
//      }
    return tailOffset;
  }

  public String toString() {
    return "COMMA";
  }
}