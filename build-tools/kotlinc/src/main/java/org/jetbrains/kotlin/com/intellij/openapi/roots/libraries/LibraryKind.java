package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public class LibraryKind {
  private final String myKindId;
  private static final Map<String, LibraryKind> ourAllKinds = new HashMap<>();

  /**
   * @param kindId must be unique among all {@link com.intellij.openapi.roots.libraries.LibraryType} and {@link com.intellij.openapi.roots.libraries.LibraryPresentationProvider} implementations
   */
  public LibraryKind(@NonNull String kindId) {
    myKindId = kindId;
    LibraryKind kind = ourAllKinds.get(kindId);
    if (kind != null && !(kind instanceof TemporaryLibraryKind)) {
      throw new IllegalArgumentException("Kind " + kindId + " is not unique");
    }
    ourAllKinds.put(kindId, this);
  }

  public final String getKindId() {
    return myKindId;
  }

  @Override
  public String toString() {
    return "LibraryKind:" + myKindId;
  }

  /**
   * @param kindId must be unique among all {@link LibraryType} and {@link LibraryPresentationProvider} implementations
   * @return new {@link LibraryKind} instance
   */
  public static LibraryKind create(@NonNull String kindId) {
    return new LibraryKind(kindId);
  }

  /**
   * @deprecated it's better to store instance of {@code LibraryKind} instead of looking it by ID; if you really need to find an instance by
   * its ID, use {@link LibraryKindRegistry#findKindById(String)}
   */
  @Deprecated
  public static LibraryKind findById(String kindId) {
    return LibraryKindRegistry.getInstance().findKindById(kindId);
  }

  static @Nullable LibraryKind findByIdInternal(@Nullable String kindId) {
    return ourAllKinds.get(kindId);
  }

  public static void unregisterKind(@NonNull LibraryKind kind) {
    ourAllKinds.remove(kind.getKindId());
  }

  public static void registerKind(@NonNull LibraryKind kind) {
    ourAllKinds.put(kind.getKindId(), kind);
  }
}