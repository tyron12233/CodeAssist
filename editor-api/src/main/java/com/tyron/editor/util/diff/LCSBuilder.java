package com.tyron.editor.util.diff;

interface LCSBuilder {
  void addEqual(int length);
  void addChange(int first, int second);
}