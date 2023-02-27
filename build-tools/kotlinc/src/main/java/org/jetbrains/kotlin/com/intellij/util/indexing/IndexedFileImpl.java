package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public class IndexedFileImpl extends UserDataHolderBase implements IndexedFile {
  protected final VirtualFile myFile;

  private volatile Project myProject;

  private String myFileName;
  private FileType mySubstituteFileType;

  private final @Nullable FileType myType;

  public IndexedFileImpl(@NonNull VirtualFile file, Project project) {
    this(file, null, project);
  }

  public IndexedFileImpl(@NonNull VirtualFile file, @Nullable FileType type, Project project) {
    myFile = file;
    myProject = project;
    myType = type;
  }

  @NonNull
  @Override
  public FileType getFileType() {
    if (mySubstituteFileType == null) {
      mySubstituteFileType = SubstitutedFileType.substituteFileType(myFile, myType != null ? myType : myFile.getFileType(), getProject());
    }
    return mySubstituteFileType;
  }

  public void setSubstituteFileType(@NonNull FileType substituteFileType) {
    mySubstituteFileType = substituteFileType;
  }

  @NonNull
  @Override
  public VirtualFile getFile() {
    return myFile;
  }

  @NonNull
  @Override
  public String getFileName() {
    if (myFileName == null) {
      myFileName = myFile.getName();
    }
    return myFileName;
  }

  @Override
  public Project getProject() {
    return myProject;
  }

  public void setProject(Project project) {
    myProject = project;
  }

  @Override
  public String toString() {
    return "IndexedFileImpl(" + getFileName() + ")";
  }
}