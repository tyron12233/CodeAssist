package org.jetbrains.kotlin.com.intellij.psi.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.UnknownFileType;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.LazyInstance;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.SubstitutedFileType;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

import javax.swing.*;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

final class FileTypeKeyDescriptor implements KeyDescriptor<FileType> {
  private final NotNullLazyValue<FileTypeNameEnumerator> myFileTypeNameEnumerator = new NotNullLazyValue<FileTypeNameEnumerator>() {
    @Override
    protected @NotNull FileTypeNameEnumerator compute() {
      return (FileTypeNameEnumerator) ((CoreFileBasedIndex) FileBasedIndex.getInstance()).getIndex(
              FileTypeIndex.NAME);
    }
  };

  @Override
  public int getHashCode(FileType value) {
    return value.getName().hashCode();
  }

  @Override
  public boolean isEqual(FileType val1, FileType val2) {
      if (val1 instanceof SubstitutedFileType) {
          val1 = ((SubstitutedFileType) val1).getOriginalFileType();
      }
      if (val2 instanceof SubstitutedFileType) {
          val2 = ((SubstitutedFileType) val2).getOriginalFileType();
      }
    if (val1 instanceof OutDatedFileType || val2 instanceof OutDatedFileType) {
      return Objects.equals(val1.getName(), val2.getName());
    }
    return Comparing.equal(val1, val2);
  }

  @Override
  public void save(@NotNull DataOutput out, FileType value) throws IOException {
    DataInputOutputUtil.writeINT(out, getFileTypeId(value.getName()));
  }

  @Override
  public FileType read(@NotNull DataInput in) throws IOException {
    String read = getFileTypeName(DataInputOutputUtil.readINT(in));
    if (read == null) {
      return UnknownFileType.INSTANCE;
    }
    FileType fileType = FileTypeRegistry.getInstance().findFileTypeByName(read);
    return fileType == null ? new OutDatedFileType(read) : fileType;
  }

  int getFileTypeId(@NotNull String fileTypeName) throws IOException {
    return myFileTypeNameEnumerator.getValue().getFileTypeId(fileTypeName);
  }

  @Nullable String getFileTypeName(int fileTypeId) throws IOException {
    return myFileTypeNameEnumerator.getValue().getFileTypeName(fileTypeId);
  }

  private static final class OutDatedFileType implements FileType {
    @NotNull
    private final String myName;

    private OutDatedFileType(@NotNull String name) {myName = name;}

    @NotNull
    @Override
    public String getName() {
      return myName;
    }

    @NotNull
    @Override
    public String getDescription() {
      throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public String getDefaultExtension() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Icon getIcon() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isBinary() {
      throw new UnsupportedOperationException();
    }

//    @Override
    public boolean isReadOnly() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public String getCharset(@NotNull VirtualFile file, byte @NotNull [] content) {
      throw new UnsupportedOperationException();
    }
  }
}