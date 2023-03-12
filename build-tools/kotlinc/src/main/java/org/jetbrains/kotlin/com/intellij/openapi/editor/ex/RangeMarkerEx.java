package org.jetbrains.kotlin.com.intellij.openapi.editor.ex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent;
import org.jetbrains.kotlin.com.intellij.openapi.util.Segment;

public interface RangeMarkerEx extends RangeMarker, Segment {
  void documentChanged(@NotNull DocumentEvent e);

  long getId();
}