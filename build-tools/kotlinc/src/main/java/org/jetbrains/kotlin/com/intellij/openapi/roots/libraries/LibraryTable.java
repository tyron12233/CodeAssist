package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;

import java.util.EventListener;
import java.util.Iterator;

public interface LibraryTable {
  Library[] getLibraries();

  @NonNull Library createLibrary();

  @NonNull Library createLibrary(String name);

  void removeLibrary(@NonNull Library library);

  @NonNull Iterator<Library> getLibraryIterator();

  @Nullable Library getLibraryByName(@NonNull String name);

  @NonNull String getTableLevel();

  @NonNull LibraryTablePresentation getPresentation();

  default boolean isEditable() {
    return true;
  }

  /**
   * Returns the interface which allows to create or removed libraries from the table.
   * <strong>The returned model must be either committed {@link ModifiableModel#commit()} or disposed {@link com.intellij.openapi.util.Disposer#dispose(Disposable)}</strong>
   *
   * @return the modifiable library table model.
   */
 @NonNull
  ModifiableModel getModifiableModel();

  void addListener(@NonNull Listener listener);

  void addListener(@NonNull Listener listener, @NonNull Disposable parentDisposable);

  void removeListener(@NonNull Listener listener);

  interface ModifiableModel extends Disposable {
    @NonNull
    Library createLibrary(String name);

    @NonNull
    Library createLibrary(String name, @Nullable PersistentLibraryKind<?> type);

//    @NonNull
//    Library createLibrary(String name, @Nullable PersistentLibraryKind<?> type, @Nullable ProjectModelExternalSource externalSource);

    void removeLibrary(@NonNull Library library);

    void commit();

    @NonNull
    Iterator<Library> getLibraryIterator();

    @Nullable
    Library getLibraryByName(@NonNull String name);

    Library[] getLibraries();

    boolean isChanged();
  }

  interface Listener extends EventListener {
//    @RequiresWriteLock(generateAssertion = false)
    default void afterLibraryAdded(@NonNull Library newLibrary) {
    }

    /**
     * @deprecated override {@link #afterLibraryRenamed(Library, String)} instead
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    default void afterLibraryRenamed(@NonNull Library library) {
    }

    default void afterLibraryRenamed(@NonNull Library library, @Nullable String oldName) {
      afterLibraryRenamed(library);
    }

    default void beforeLibraryRemoved(@NonNull Library library) {
    }

    default void afterLibraryRemoved(@NonNull Library library) {
    }
  }
}