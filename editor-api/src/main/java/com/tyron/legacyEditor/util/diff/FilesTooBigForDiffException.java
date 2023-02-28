package com.tyron.legacyEditor.util.diff;

/**
 * @author irengrig
 */
public class FilesTooBigForDiffException extends Exception {
  public FilesTooBigForDiffException() {
    super("Can not calculate diff. File is too big and there are too many changes.");
  }
}