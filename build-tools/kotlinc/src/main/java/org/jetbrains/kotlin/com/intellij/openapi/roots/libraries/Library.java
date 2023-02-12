package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootProvider;
import org.jetbrains.kotlin.com.intellij.openapi.util.JDOMExternalizable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface Library extends JDOMExternalizable, Disposable {
  Library[] EMPTY_ARRAY = new Library[0];

  /**
   * Returns name for the library or {@code null} if the library doesn't have a name (it's possible to create a module level library without
   * specifying a name for it).
   */
  @Nullable
  String getName();

  /**
   * Returns name of the library to show in UI. If the library has a {@link #getName() name} specified by user it is returned; for unnamed
   * module-level library name of its first file is returned.
   */
  @NonNull
  String getPresentableName();

  String[] getUrls(@NonNull OrderRootType rootType);

  VirtualFile[] getFiles(@NonNull OrderRootType rootType);

  /**
   * As soon as you obtaining modifiable model you will have to commit it or call Disposer.dispose(model)!
   */
  @NonNull
  ModifiableModel getModifiableModel();

  LibraryTable getTable();

  @NonNull
  RootProvider getRootProvider();

  boolean isJarDirectory(@NonNull String url);

  boolean isJarDirectory(@NonNull String url, @NonNull OrderRootType rootType);

  boolean isValid(@NonNull String url, @NonNull OrderRootType rootType);

  /**
   * Compares the content of the current instance of the library with the given one.
   * @param library to compare with
   * @return true if the content is same
   */
  boolean hasSameContent(@NonNull Library library);

  interface ModifiableModel extends Disposable {
    String[] getUrls(@NonNull OrderRootType rootType);

    void setName(String name);

    String getName();

    void addRoot(@NonNull String url, @NonNull OrderRootType rootType);

    void addJarDirectory(@NonNull String url, boolean recursive);

    void addJarDirectory(@NonNull String url, boolean recursive, @NonNull OrderRootType rootType);

    void addRoot(@NonNull VirtualFile file, @NonNull OrderRootType rootType);

    void addJarDirectory(@NonNull VirtualFile file, boolean recursive);

    void addJarDirectory(@NonNull VirtualFile file, boolean recursive, @NonNull OrderRootType rootType);

    void moveRootUp(@NonNull String url, @NonNull OrderRootType rootType);

    void moveRootDown(@NonNull String url, @NonNull OrderRootType rootType);

    boolean removeRoot(@NonNull String url, @NonNull OrderRootType rootType);

    void commit();

    VirtualFile[] getFiles(@NonNull OrderRootType rootType);

    boolean isChanged();

    boolean isJarDirectory(@NonNull String url);

    boolean isJarDirectory(@NonNull String url, @NonNull OrderRootType rootType);

    boolean isValid(@NonNull String url, @NonNull OrderRootType rootType);
  }
}