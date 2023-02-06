package com.tyron.code.highlighter.lexer;


import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.StringEscapesTokenTypes;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

class JavaStringLiteralLexer extends StringLiteralLexer {

  JavaStringLiteralLexer(char quoteChar, IElementType originalLiteralToken) {
    super(quoteChar, originalLiteralToken);
  }

  JavaStringLiteralLexer(char quoteChar,
                         IElementType originalLiteralToken,
                         boolean canEscapeEolOrFramingSpaces,
                         String additionalValidEscapes) {
    super(quoteChar, originalLiteralToken, canEscapeEolOrFramingSpaces, additionalValidEscapes);
  }

  @Override
  protected @NonNull
  IElementType getUnicodeEscapeSequenceType() {
    int start = myStart + 2;
    while (start < myEnd && myBuffer.charAt(start) == 'u') start++;
      if (start + 3 >= myEnd) {
          return StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
      }
    if (!StringUtil.isHexDigit(myBuffer.charAt(start)) ||
        !StringUtil.isHexDigit(myBuffer.charAt(start + 1)) ||
        !StringUtil.isHexDigit(myBuffer.charAt(start + 2)) ||
        !StringUtil.isHexDigit(myBuffer.charAt(start + 3))) {
      return StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN;
    }
    return StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN;
  }

  @Override
  protected int locateUnicodeEscapeSequence(int start, int i) {
    do {
      i++;
    }
    while (i < myBufferEnd && myBuffer.charAt(i) == 'u');
    int end = parseUnicodeDigits(i);
      if (end != i + 4) {
          return end;
      }
    int code = Integer.parseInt(myBuffer.subSequence(i, end).toString(), 16);
    i = end;
    // if escape sequence is not translated to backspace then continue from the next symbol
      if (code != '\\' || i >= myBufferEnd) {
          return i;
      }
    char c = myBuffer.charAt(i);
    if (StringUtil.isOctalDigit(c)) {
      if (i + 2 < myBufferEnd && StringUtil.isOctalDigit(myBuffer.charAt(i + 1)) && StringUtil.isOctalDigit(myBuffer.charAt(i + 1))) {
        return i + 3;
      }
    }
    else if (c == '\\' && i + 1 < myBufferEnd && myBuffer.charAt(i + 1) == 'u') {
      i += 2;
      while (i < myBufferEnd && myBuffer.charAt(i) == 'u') i++;
      return parseUnicodeDigits(i);
    }
    return i + 1;
  }

  private int parseUnicodeDigits(int i) {
    int end = i + 4;
    for (; i < end; i++) {
        if (i == myBufferEnd) {
            return i;
        }
        if (!StringUtil.isHexDigit(myBuffer.charAt(i))) {
            return i;
        }
    }
    return end;
  }
}