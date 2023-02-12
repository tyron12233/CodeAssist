package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.PathsList;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.NotNullFunction;

public interface OrderRootsEnumerator {
  /**
   * @return all roots processed by this enumerator
   */
  VirtualFile[] getRoots();

  /**
   * @return urls of all roots processed by this enumerator
   */
  String[] getUrls();

  /**
   * @return list of path to all roots processed by this enumerator
   */
  @NonNull
  PathsList getPathsList();

  /**
   * Add all source roots processed by this enumerator to {@code list}
   * @param list list
   */
  void collectPaths(@NonNull PathsList list);

  /**
   * If roots for this enumerator are already evaluated the cached result will be used. Otherwise roots will be evaluated and cached for
   * subsequent calls. <p>
   * Caching is not supported if {@link OrderEnumerator#satisfying}, {@link OrderEnumerator#using} or {@link #usingCustomRootProvider}
   * option is used
   * @return this instance
   */
  @NonNull
  OrderRootsEnumerator usingCache();

  /**
   * This method makes sense only when dependencies of a module are processed (i.e. the enumerator instance is obtained by using {@link OrderEnumerator#orderEntries(com.intellij.openapi.module.Module)} or
   * {@link ModuleRootModel#orderEntries()}). It instructs the enumerator to skip the output of the main module (if {@link OrderEnumerator#productionOnly()}
   * option is not specified then only the test output will be skipped)
   *
   * @return this instance
   */
  @NonNull
  OrderRootsEnumerator withoutSelfModuleOutput();

  /**
   * @deprecated use {@link #usingCustomSdkRootProvider(NotNullFunction)} instead.
   */
  @Deprecated
  @NonNull
  OrderRootsEnumerator usingCustomRootProvider(@NonNull NotNullFunction<? super OrderEntry, VirtualFile[]> provider);

  /**
   * Instructs the enumerator to use {@code provider} to obtain roots of an SDK order entry instead of taking them from SDK configuration. 
   * Note that this option won't affect the result of {@link #getUrls()} method
   * 
   * @return this instance
   */
  @NonNull
  OrderRootsEnumerator usingCustomSdkRootProvider(@NonNull NotNullFunction<? super JdkOrderEntry, VirtualFile[]> provider);
}