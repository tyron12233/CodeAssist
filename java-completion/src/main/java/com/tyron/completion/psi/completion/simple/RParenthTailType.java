package com.tyron.completion.psi.completion.simple;

import com.tyron.completion.TailType;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiLoopStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiStatement;
import org.jetbrains.kotlin.com.intellij.psi.TokenType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.ElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.java.IJavaElementType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

import io.github.rosemoe.sora.widget.CodeEditor;

public abstract class RParenthTailType extends TailType {
  private static final Logger LOG = Logger.getInstance(RParenthTailType.class);

  private static TextRange getRangeToCheckParensBalance(PsiFile file, final Document document, int startOffset){
    PsiElement element = file.findElementAt(startOffset);
    element = PsiTreeUtil.getParentOfType(element, PsiStatement.class, false);
    if (element != null) {
      final PsiElement parent = element.getParent();
      if (parent instanceof PsiLoopStatement) {
        element = parent;
      }
    }
    return element == null ? new TextRange(0, document.getTextLength()) : element.getTextRange();
  }

  protected abstract boolean isSpaceWithinParentheses(Object styleSettings, Editor editor, final int tailOffset);

  @Override
  public int processTail(final Editor editor, int tailOffset) {
    return addRParenth(editor, tailOffset,
                       isSpaceWithinParentheses(null, editor, tailOffset));
  }

  public static int addRParenth(Editor editor, int offset, boolean spaceWithinParens) {
    int existingRParenthOffset = -1;

    if (existingRParenthOffset < 0){
      if (spaceWithinParens){
        offset = insertChar(editor, offset, ' ');
      }
      editor.getDocument().insertString(offset, ")");
      return moveCaret(editor, offset, 1);
    }
    if (spaceWithinParens && offset == existingRParenthOffset) {
      existingRParenthOffset = insertChar(editor, offset, ' ');
    }
    return moveCaret(editor, existingRParenthOffset, 1);
  }

  @NonNls
  public String toString() {
    return "RParenth";
  }

//  private static int getExistingRParenthOffset(final CodeEditor editor, final int tailOffset) {
//    final Document document = editor.getDocument();
//      if (tailOffset >= document.getTextLength()) {
//          return -1;
//      }
//
//    final CharSequence charsSequence = document.getCharsSequence();
//    EditorHighlighter highlighter = editor.getHighlighter();
//
//    int existingRParenthOffset = -1;
//    for(HighlighterIterator iterator = highlighter.createIterator(tailOffset); !iterator.atEnd(); iterator.advance()){
//      final IElementType tokenType = iterator.getTokenType();
//
//      if ((!(tokenType instanceof IJavaElementType) || !ElementType.JAVA_COMMENT_OR_WHITESPACE_BIT_SET.contains(tokenType)) &&
//          tokenType != TokenType.WHITE_SPACE) {
//        final int start = iterator.getStart();
//        if (iterator.getEnd() == start + 1 &&  ')' == charsSequence.charAt(start)) {
//          existingRParenthOffset = start;
//        }
//        break;
//      }
//    }
//
//    if (existingRParenthOffset >= 0){
//      final PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(editor.getProject());
//      psiDocumentManager.commitDocument(document);
//      TextRange range = getRangeToCheckParensBalance(psiDocumentManager.getPsiFile(document), document, editor.getCaretModel().getOffset());
//      int balance = calcParensBalance(document, highlighter, range.getStartOffset(), range.getEndOffset());
//      if (balance > 0){
//        return -1;
//      }
//    }
//    return existingRParenthOffset;
//  }
//
//  private static int calcParensBalance(Document document, EditorHighlighter highlighter, int rangeStart, int rangeEnd){
//    LOG.assertTrue(0 <= rangeStart);
//    LOG.assertTrue(rangeStart <= rangeEnd);
//    LOG.assertTrue(rangeEnd <= document.getTextLength());
//
//    HighlighterIterator iterator = highlighter.createIterator(rangeStart);
//    int balance = 0;
//    while(!iterator.atEnd() && iterator.getStart() < rangeEnd){
//      IElementType tokenType = iterator.getTokenType();
//      if (tokenType == JavaTokenType.LPARENTH){
//        balance++;
//      }
//      else if (tokenType == JavaTokenType.RPARENTH){
//        balance--;
//      }
//      iterator.advance();
//    }
//    return balance;
//  }

}