package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

/**
 * Represents a source or test source root under the content root of a module.
 *
 * @see ContentEntry#getSourceFolders()
 */
public interface SourceFolder extends ContentFolder {
  /**
   * Checks if this root is a production or test source root.
   *
   * @return true if this source root is a test source root, false otherwise.
   */
  boolean isTestSource();

  /**
   * Returns the package prefix for this source root.
   *
   * @return the package prefix, or an empty string if the root has no package prefix.
   */
  @NonNull
  String getPackagePrefix();

  /**
   * Sets the package prefix for this source root. This method may be called only on a modifiable instance obtained from {@link ModifiableRootModel}.
   *
   * @param packagePrefix the package prefix, or an empty string if the root has no package prefix.
   */
  void setPackagePrefix(@NonNull String packagePrefix);

//  @NonNull
//  JpsModuleSourceRootType<?> getRootType();
//
//  @NonNull
//  JpsModuleSourceRoot getJpsElement();
//
//  /**
//   * This method is used internally to change root type to 'unknown' and back when the plugin which provides the custom root type is
//   * unloaded or loader. It isn't intended to change root type to some other arbitrary type and must not be used in plugins.
//   */
//  @ApiStatus.Internal
//  <P extends JpsElement> void changeType(JpsModuleSourceRootType<P> newType, P properties);
}