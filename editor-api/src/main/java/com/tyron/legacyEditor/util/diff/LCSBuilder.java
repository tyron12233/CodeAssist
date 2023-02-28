package com.tyron.legacyEditor.util.diff;

interface LCSBuilder {
  void addEqual(int length);
  void addChange(int first, int second);
}