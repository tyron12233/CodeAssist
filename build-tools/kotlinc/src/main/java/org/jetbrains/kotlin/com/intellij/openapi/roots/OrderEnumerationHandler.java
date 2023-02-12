package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;

import java.util.Collection;

/**
 * Implement this extension to change how dependencies of modules are processed by the IDE. You may also need to register implementation of
 * {@link org.jetbrains.jps.model.java.impl.JpsJavaDependenciesEnumerationHandler} extension to ensure that the same logic applies inside
 * JPS build process.
 */
public abstract class OrderEnumerationHandler {
  public static final ExtensionPointName<Factory> EP_NAME =
    ExtensionPointName.create("com.intellij.orderEnumerationHandlerFactory");

  public abstract static class Factory {
    public abstract boolean isApplicable(@NonNull Module module);

    @NonNull
    public abstract OrderEnumerationHandler createHandler(@NonNull Module module);
  }

  public enum AddDependencyType {ADD, DO_NOT_ADD, DEFAULT}

  @NonNull
  public AddDependencyType shouldAddDependency(@NonNull OrderEntry orderEntry,
                                               @NonNull OrderEnumeratorSettings settings) {
    return AddDependencyType.DEFAULT;
  }

  public boolean shouldAddRuntimeDependenciesToTestCompilationClasspath() {
    return false;
  }

  public boolean shouldIncludeTestsFromDependentModulesToTestClasspath() {
    return true;
  }

  public boolean shouldProcessDependenciesRecursively() {
    return true;
  }

  /**
   * Returns {@code true} if resource files located under roots of types {@link org.jetbrains.jps.model.java.JavaModuleSourceRootTypes#SOURCES}
   * are copied to the module output.
   */
  public boolean areResourceFilesFromSourceRootsCopiedToOutput() {
    return true;
  }

  /**
   * Override this method to contribute custom roots for a library or SDK instead of the configured ones.
   * @return {@code false} if no customization was performed, and therefore the default roots should be added.
   */
  public boolean addCustomRootsForLibraryOrSdk(@NonNull LibraryOrSdkOrderEntry forOrderEntry,
                                               @NonNull OrderRootType type,
                                               @NonNull Collection<String> urls) {
    return addCustomRootsForLibrary(forOrderEntry, type, urls);
  }

  /**
   * @deprecated override {@link #addCustomRootsForLibraryOrSdk(LibraryOrSdkOrderEntry, OrderRootType, Collection)} instead.
   */
  @Deprecated
  public boolean addCustomRootsForLibrary(@NonNull OrderEntry forOrderEntry,
                                          @NonNull OrderRootType type,
                                          @NonNull Collection<String> urls) {
    return false;
  }

  public boolean addCustomModuleRoots(@NonNull OrderRootType type,
                                      @NonNull ModuleRootModel rootModel,
                                      @NonNull Collection<String> result,
                                      boolean includeProduction,
                                      boolean includeTests) {
    return false;
  }
}