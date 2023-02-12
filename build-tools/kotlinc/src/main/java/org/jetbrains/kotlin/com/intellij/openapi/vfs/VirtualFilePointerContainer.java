package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.InvalidDataException;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.w3c.dom.Element;

import java.util.List;

/**
 * Modifiable and persistable set of {@link VirtualFilePointer}s.
 *
 * @see VirtualFilePointerManagerEx#createContainer
 */
public interface VirtualFilePointerContainer {
  void killAll();

  void add(@NonNull VirtualFile file);

  void add(@NonNull String url);

  void remove(@NonNull VirtualFilePointer pointer);

  @NonNull
  List<VirtualFilePointer> getList();

  void addAll(@NonNull VirtualFilePointerContainer that);

  String[] getUrls();

  boolean isEmpty();

  VirtualFile[] getFiles();

  VirtualFile[] getDirectories();

  @Nullable
  VirtualFilePointer findByUrl(@NonNull String url);

  void clear();

  int size();

  /**
   * For example, to read from the xml below, call {@code readExternal(myRootTag, "childElementName"); }
   * <pre>{@code
   * <myroot>
   *   <childElementName url="xxx1"/>
   *   <childElementName url="xxx2"/>
   * </myroot>
   * }</pre>
   */
  void readExternal(@NonNull Element rootChild, @NonNull String childElementName, boolean externalizeJarDirectories)
    throws InvalidDataException;

  void writeExternal(@NonNull Element element, @NonNull String childElementName, boolean externalizeJarDirectories);

  void moveUp(@NonNull String url);

  void moveDown(@NonNull String url);

  @NonNull
  VirtualFilePointerContainer clone(@NonNull Disposable parent);

  @NonNull
  VirtualFilePointerContainer clone(@NonNull Disposable parent, @Nullable VirtualFilePointerListener listener);

  /**
   * Adds {@code directory} as a root of jar files.
   * After this call the {@link #getFiles()} will additionally return jar files in this directory
   * (and, if {@code recursively} was set, the jar files in all-subdirectories).
   * {@link #getUrls()} will additionally return the {@code directoryUrl}.
   */
  void addJarDirectory(@NonNull String directoryUrl, boolean recursively);

  /**
   * Removes {@code directory} from the roots of jar files.
   * After that the {@link #getFiles()} and {@link #getUrls()} etc will not return jar files in this directory anymore.
   *
   * @return true if removed
   */
  boolean removeJarDirectory(@NonNull String directoryUrl);

  /**
   * Returns list of (directory url, isRecursive) which were added via {@link #addJarDirectory(String, boolean)} }
   */
  @NonNull
  List<Pair<String, Boolean>> getJarDirectories();
}