package com.tyron.builder.ide.common.blame.parser.util;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Reads a compiler's output line-by-line.
 */
public class OutputLineReader {
  private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");

  @NonNull private final String[] myLines;

  private final int myLineCount;
  private int myPosition;

  /**
   * Creates a new {@link OutputLineReader}.
   *
   * @param text the text to read.
   */
  public OutputLineReader(@NonNull String text) {
    myLines = LINE_BREAK.split(text);
    myLineCount = myLines.length;
  }

  public int getLineCount() {
    return myLineCount;
  }

  /**
   * Reads the next line of text, moving the line pointer to the next one.
   *
   * @return the contents of the next line, or {@code null} if we reached the end of the text.
   */
  @Nullable
  public String readLine() {
    if (myPosition >= 0 && myPosition < myLineCount) {
      return myLines[myPosition++];
    }
    return null;
  }

  /**
   * Reads the text of one the line at the given position, without moving the line pointer.
   *
   * @param lineToSkipCount the number of lines to skip from the line pointer.
   * @return the contents of the specified line, or {@code null} if the specified position is greater than the end of the text.
   */
  @Nullable
  public String peek(int lineToSkipCount) {
    int tempPosition = lineToSkipCount + myPosition;
    if (tempPosition >= 0 && tempPosition < myLineCount) {
      return myLines[tempPosition];
    }
    return null;
  }

  public boolean hasNextLine() {
    return myPosition < myLineCount - 1;
  }

  public void skipNextLine() {
    myPosition++;
  }

  public void pushBack() {
    myPosition--;
  }
}
