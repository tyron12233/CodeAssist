package com.tyron.builder.ide.common.blame.parser.util;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

public class ParserUtil {
  private ParserUtil() {
  }

  @Nullable
  public static String digestStackTrace(@NonNull OutputLineReader reader) {
    String next = reader.peek(0);
    if (next == null) {
      return null;
    }

    int index = next.indexOf(':');
    if (index == -1) {
      return null;
    }

    String message = null;
    String exceptionName = next.substring(0, index);
    if (exceptionName.endsWith("Exception") || exceptionName.endsWith("Error")) {
      message = next.substring(index + 1).trim();
      reader.readLine();

      // Digest stack frames below it
      while (true) {
        String peek = reader.peek(0);
        if (peek != null && peek.startsWith("\tat")) {
          reader.readLine();
        } else {
          break;
        }
      }
    }

    return message;
  }
}