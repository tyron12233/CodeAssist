package org.jetbrains.kotlin.com.intellij.psi.search;

import java.io.IOException;

public interface FileTypeNameEnumerator {
  int getFileTypeId(String name) throws IOException;

  String getFileTypeName(int id) throws IOException;
}