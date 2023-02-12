package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root types that can be queried from OrderEntry.
 *
 * @see OrderEntry
 */
public class OrderRootType {
  private final String myName;
  private static boolean ourExtensionsLoaded;

  public static final ExtensionPointName<OrderRootType> EP_NAME = ExtensionPointName.create("com.intellij.orderRootType");

  static PersistentOrderRootType[] ourPersistentOrderRootTypes = new PersistentOrderRootType[0];

  protected OrderRootType(@NonNull String name) {
    myName = name;
  }

  /**
   * Classpath without output directories for modules. Includes:
   * <ul>
   * <li>classes roots for libraries and jdk</li>
   * <li>recursively for module dependencies: only exported items</li>
   * </ul>
   */
  public static final OrderRootType CLASSES = new PersistentOrderRootType("CLASSES", "classPath", null, "classPathEntry");

  /**
   * Sources. Includes:
   * <ul>
   * <li>production and test source roots for modules</li>
   * <li>source roots for libraries and jdk</li>
   * <li>recursively for module dependencies: only exported items</li>
   * </ul>
   */
  public static final OrderRootType SOURCES = new PersistentOrderRootType("SOURCES", "sourcePath", null, "sourcePathEntry");

  /**
   * Generic documentation order root type
   */
  public static final OrderRootType DOCUMENTATION = new DocumentationRootType();

  /**
   * A temporary solution to exclude DOCUMENTATION from getAllTypes() and handle it only in special
   * cases if supported by LibraryType.
   */
  public static class DocumentationRootType extends OrderRootType {
    public DocumentationRootType() {
      super("DOCUMENTATION");
    }

    @Override
    public boolean skipWriteIfEmpty() {
      return true;
    }
  }

  @NonNull
  public String name() {
    return myName;
  }

  /**
   * Whether this root type should be skipped when writing a Library if the root type doesn't contain
   * any roots.
   *
   * @return true if empty root type should be skipped, false otherwise.
   */
  public boolean skipWriteIfEmpty() {
    return false;
  }

  public static synchronized OrderRootType[] getAllTypes() {
    return getAllPersistentTypes();
  }

  public static PersistentOrderRootType[] getAllPersistentTypes() {
    if (!ourExtensionsLoaded) {
      ourExtensionsLoaded = true;
      EP_NAME.getExtensionList();
    }
    return ourPersistentOrderRootTypes;
  }

  @NonNull
  public static List<PersistentOrderRootType> getSortedRootTypes() {
    List<PersistentOrderRootType> allTypes = new ArrayList<>();
    Collections.addAll(allTypes, getAllPersistentTypes());
    allTypes.sort((o1, o2) -> o1.name().compareToIgnoreCase(o2.name()));
    return allTypes;
  }

  @NonNull
  protected static <T> T getOrderRootType(@NonNull Class<? extends T> orderRootTypeClass) {
    List<OrderRootType> rootTypes = EP_NAME.getExtensionList();
    for (OrderRootType rootType : rootTypes) {
      if (orderRootTypeClass.isInstance(rootType)) {
        @SuppressWarnings("unchecked") T t = (T)rootType;
        return t;
      }
    }
    assert false : "Root type " + orderRootTypeClass + " not found. All roots: " + rootTypes;
    return null;
  }

  @Override
  public final int hashCode() {
    return super.hashCode();
  }

  @Override
  public final boolean equals(Object obj) {
    return super.equals(obj);
  }

  @Override
  public String toString() {
    return "Root " + name();
  }
}