package org.jetbrains.kotlin.com.intellij.openapi.editor.impl;

import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.LineIterator;

public class LineIteratorImpl implements LineIterator {
  private int myLineIndex = 0;
  private final LineSet myLineSet;

  LineIteratorImpl(LineSet lineSet) {
    myLineSet = lineSet;
  }

  @Override
  public void start(int startOffset) {
    myLineIndex = myLineSet.findLineIndex(startOffset);
  }

  @Override
  public int getStart() {
    return myLineSet.getLineStart(myLineIndex);
  }

  @Override
  public int getEnd() {
    return myLineSet.getLineEnd(myLineIndex);
  }

  @Override
  public int getSeparatorLength() {
    return myLineSet.getSeparatorLength(myLineIndex);
  }

  @Override
  public int getLineNumber() {
    return myLineIndex;
  }

  @Override
  public void advance() {
    myLineIndex++;
  }

  @Override
  public boolean atEnd() {
    return myLineIndex >= myLineSet.getLineCount() || myLineIndex < 0;
  }


}