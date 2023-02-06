package com.tyron.code.highlighter;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

/**
 * Controls the syntax highlighting of a file.
 *
 * Extend {@link SyntaxHighlighterBase}.
 *
 * @see SyntaxHighlighterFactory#getSyntaxHighlighter(com.intellij.openapi.project.Project, com.intellij.openapi.vfs.VirtualFile)
 * @see SyntaxHighlighterFactory#getSyntaxHighlighter(com.intellij.lang.Language, com.intellij.openapi.project.Project, com.intellij.openapi.vfs.VirtualFile)
 */
public interface SyntaxHighlighter {
  ExtensionPointName<SyntaxHighlighter> EP_NAME = ExtensionPointName.create("com.intellij.syntaxHighlighter");

  /**
   * Returns the lexer used for highlighting the file. The lexer is invoked incrementally when the file is changed, so it must be
   * capable of saving/restoring state and resuming lexing from the middle of the file.
   *
   * @return The lexer implementation.
   */
  @NonNull
  Lexer getHighlightingLexer();

  /**
   * Returns the list of text attribute keys used for highlighting the specified token type. The attributes of all attribute keys
   * returned for the token type are successively merged to obtain the color and attributes of the token.
   *
   * @param tokenType The token type for which the highlighting is requested.
   * @return The array of text attribute keys.
   */
  TextAttributesKey[] getTokenHighlights(IElementType tokenType);
}