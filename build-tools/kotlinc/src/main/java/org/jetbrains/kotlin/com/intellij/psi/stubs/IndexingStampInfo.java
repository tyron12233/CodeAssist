package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

/**
 * An informational object for debugging stub-mismatch related issues. Should be as small as possible since it's stored in files's attributes.
 */
class IndexingStampInfo {
  final long indexingFileStamp;
  final long indexingByteLength;
  final int indexingCharLength;
  final boolean isBinary;

  IndexingStampInfo(long indexingFileStamp, long indexingByteLength, int indexingCharLength, boolean isBinary) {
    this.indexingFileStamp = indexingFileStamp;
    this.indexingByteLength = indexingByteLength;
    this.indexingCharLength = indexingCharLength;
    this.isBinary = isBinary;
  }

  @Override
  public String toString() {
    return "indexing timestamp = " + indexingFileStamp + ", " +
           "binary = " + isBinary + ", byte size = " + indexingByteLength + ", char size = " + indexingCharLength;
  }

  public boolean isUpToDate(@Nullable Document document, @NotNull VirtualFile file, @NotNull PsiFile psi) {
    if (document == null ||
        FileDocumentManager.getInstance().isDocumentUnsaved(document) ||
        !PsiDocumentManager.getInstance(psi.getProject()).isCommitted(document)) {
      return false;
    }

    boolean isFileBinary = file.getFileType().isBinary();
    return indexingFileStamp == file.getTimeStamp() &&
           isBinary == isFileBinary &&
           contentLengthMatches(file.getLength(), document.getTextLength());
  }

  public boolean contentLengthMatches(long byteContentLength, int charContentLength) {
    if (this.indexingCharLength >= 0 && charContentLength >= 0) {
      return this.indexingCharLength == charContentLength;
    }
    //
    // Due to VFS implementation reasons we cannot guarantee file.getLength() and VFS events consistency.
    // In this case we prefer to skip this check and leave `indexingByteLength` value only for informational reasons.
    //
    return true; //this.indexingByteLength == byteContentLength;
  }
}