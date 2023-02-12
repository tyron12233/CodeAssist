package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;

import java.util.List;
import java.util.Set;

/**
 * Interface providing root information model for a given module.
 * It's implemented by {@link ModuleRootManager}.
 */
public interface ModuleRootModel {
  /**
   * Returns the module to which the model belongs.
   *
   * @return the module instance.
   */
  @NonNull
  Module getModule();

  /**
   * Use this method to obtain all content entries of a module. Entries are given in
   * lexicographical order of their paths.
   *
   * @return array of content entries for this module
   * @see ContentEntry
   */
  ContentEntry[] getContentEntries();

  /**
   * Use this method to obtain order of roots of a module. Order of entries is important.
   *
   * @return array of order entries for this module
   */
  OrderEntry[] getOrderEntries();

  /**
   * Returns the SDK used by the module.
   *
   * @return either module-specific or inherited SDK
   * @see #isSdkInherited()
   */
  @Nullable
  Sdk getSdk();

  /**
   * Returns {@code true} if SDK for this module is inherited from a project.
   *
   * @return true if the SDK is inherited, false otherwise
   * @see ProjectRootManager#getProjectSdk()
   * @see ProjectRootManager#setProjectSdk(Sdk)
   */
  boolean isSdkInherited();

  /**
   * Returns an array of content roots from all content entries.
   *
   * @return the array of content roots.
   * @see #getContentEntries()
   */
  @NonNull
  VirtualFile[] getContentRoots();

  /**
   * Returns an array of content root urls from all content entries.
   *
   * @return the array of content root URLs.
   * @see #getContentEntries()
   */
  @NonNull
  String[] getContentRootUrls();

  /**
   * Returns an array of exclude roots from all content entries.
   *
   * @return the array of excluded roots.
   * @see #getContentEntries()
   */
  @NonNull
  VirtualFile[] getExcludeRoots();

  /**
   * Returns an array of exclude root urls from all content entries.
   *
   * @return the array of excluded root URLs.
   * @see #getContentEntries()
   */
  @NonNull
  String[] getExcludeRootUrls();

  /**
   * Returns an array of source roots from all content entries.
   *
   * @return the array of source roots.
   * @see #getContentEntries()
   * @see #getSourceRoots(boolean)
   */
  @NonNull VirtualFile[] getSourceRoots();

  /**
   * Returns an array of source roots from all content entries.
   *
   * @param includingTests determines whether test source roots should be included in the result
   * @return the array of source roots.
   * @see #getContentEntries()
   */
  @NonNull
  VirtualFile[] getSourceRoots(boolean includingTests);

//  /**
//   * Return a list of source roots of the specified type.
//   *
//   * @param rootType type of source roots
//   * @return list of source roots
//   */
//  @NonNull
//  List<VirtualFile> getSourceRoots(@NonNull JpsModuleSourceRootType<?> rootType);
//
//  /**
//   * Return a list of source roots which types belong to the specified set.
//   *
//   * @param rootTypes types of source roots
//   * @return list of source roots
//   */
//  @NonNull
//  List<VirtualFile> getSourceRoots(@NonNull Set<? extends JpsModuleSourceRootType<?>> rootTypes);

  /**
   * Returns an array of source root urls from all content entries.
   *
   * @return the array of source root URLs.
   * @see #getContentEntries()
   * @see #getSourceRootUrls(boolean)
   */
  @NonNull
  String[] getSourceRootUrls();

  /**
   * Returns an array of source root urls from all content entries.
   *
   * @param includingTests determines whether test source root urls should be included in the result
   * @return the array of source root URLs.
   * @see #getContentEntries()
   */
  @NonNull
  String[] getSourceRootUrls(boolean includingTests);

  /**
   * Passes all order entries in the module to the specified visitor.
   *
   * @param policy       the visitor to accept.
   * @param initialValue the default value to be returned by the visit process.
   * @return the value returned by the visitor.
   * @see OrderEntry#accept(RootPolicy, Object)
   */
  <R> R processOrder(@NonNull RootPolicy<R> policy, R initialValue);

  /**
   * Returns {@link OrderEnumerator} instance which can be used to process order entries of the module (with or without dependencies) and
   * collect classes or source roots.
   *
   * @return {@link OrderEnumerator} instance
   */
  @NonNull
  OrderEnumerator orderEntries();

  /**
   * Returns list of module names <i>this module</i> depends on.
   *
   * @return the array of module names this module depends on.
   */
  @NonNull
  String[] getDependencyModuleNames();

  <T> T getModuleExtension(@NonNull Class<T> klass);

  @NonNull
  Module[] getModuleDependencies();

  @NonNull
  Module[] getModuleDependencies(boolean includeTests);
}