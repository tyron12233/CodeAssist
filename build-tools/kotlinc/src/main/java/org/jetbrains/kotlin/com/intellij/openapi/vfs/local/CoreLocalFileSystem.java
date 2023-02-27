package org.jetbrains.kotlin.com.intellij.openapi.vfs.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileAttributes;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.CoreLocalVirtualFileEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.RefreshQueue;
import org.jetbrains.kotlin.org.jline.utils.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

public class CoreLocalFileSystem extends NewVirtualFileSystem {

  private static final @NotNull Logger LOG = Logger.getInstance(CoreLocalFileSystem.class);

  public CoreLocalFileSystem() {

  }

  @Override
  public @NonNull String getProtocol() {
    return StandardFileSystems.FILE_PROTOCOL;
  }

  public @Nullable VirtualFile findFileByIoFile(@NonNull File file) {
    return findFileByNioFile(file.toPath());
  }

  public @Nullable VirtualFile findFileByNioFile(@NonNull Path file) {
    return Files.exists(file) ? new CoreLocalVirtualFileEx(this, file.toFile()) : null;
  }

  @Override
  public VirtualFile findFileByPath(@NonNull String path) {
    return findFileByNioFile(FileSystems.getDefault().getPath(path));
  }

  @Override
  public void refresh(boolean asynchronous) {

  }

  @Override
  public VirtualFile refreshAndFindFileByPath(@NonNull String path) {
    return findFileByPath(path);
  }

  @Override
  public @Nullable Path getNioPath(@NonNull VirtualFile file) {
    return file.getFileSystem() == this && file instanceof CoreLocalVirtualFileEx ? ((CoreLocalVirtualFileEx)file).toNioPath() : null;
  }

  @Override
  public boolean exists(@NonNull VirtualFile file) {
    return file.exists();
  }

  @Override
  public String[] list(@NonNull VirtualFile file) {
    return new String[0];
  }

  @Override
  public boolean isDirectory(@NonNull VirtualFile file) {
    return file.isDirectory();
  }

  @Override
  public long getTimeStamp(@NonNull VirtualFile file) {
    return file.getTimeStamp();
  }

  @Override
  public void setTimeStamp(@NonNull VirtualFile file, long timeStamp) throws IOException {

  }

  @Override
  public boolean isWritable(@NonNull VirtualFile file) {
    return file.isWritable();
  }

  @Override
  public void setWritable(@NonNull VirtualFile file, boolean writableFlag) throws IOException {

  }

  @Override
  public byte[] contentsToByteArray(@NonNull VirtualFile file) throws IOException {
    return new byte[0];
  }

  @NonNull
  @Override
  public InputStream getInputStream(@NonNull VirtualFile file) throws IOException {
    return file.getInputStream();
  }

  @NonNull
  @Override
  public OutputStream getOutputStream(@NonNull VirtualFile file,
                                      Object requestor,
                                      long modStamp,
                                      long timeStamp) throws IOException {
    return file.getOutputStream(requestor, modStamp, timeStamp);
  }

  @Override
  public long getLength(@NonNull VirtualFile file) {
    return file.getLength();
  }

  @NonNull
  @Override
  protected String extractRootPath(@NonNull String normalizedPath) {
    return null;
  }

  @Nullable
  @Override
  public VirtualFile findFileByPathIfCached(@NonNull String path) {
    return null;
  }

  @Override
  public int getRank() {
    return 0;
  }

  @NonNull
  @Override
  public VirtualFile copyFile(Object requestor,
                              @NonNull VirtualFile file,
                              @NonNull VirtualFile newParent,
                              @NonNull String copyName) throws IOException {
    return null;
  }

  @NonNull
  @Override
  public VirtualFile createChildDirectory(Object requestor,
                                          @NonNull VirtualFile parent,
                                          @NonNull String dir) throws IOException {
    return null;
  }

  @NonNull
  @Override
  public VirtualFile createChildFile(Object requestor,
                                     @NonNull VirtualFile parent,
                                     @NonNull String file) throws IOException {
    return null;
  }

  @Override
  public void deleteFile(Object requestor, @NonNull VirtualFile file) throws IOException {

  }

  @Override
  public void moveFile(Object requestor,
                       @NonNull VirtualFile file,
                       @NonNull VirtualFile newParent) throws IOException {

  }

  @Override
  public void renameFile(Object requestor,
                         @NonNull VirtualFile file,
                         @NonNull String newName) throws IOException {

  }

  @Nullable
  @Override
  public FileAttributes getAttributes(@NonNull VirtualFile file) {
    return null;
  }
}