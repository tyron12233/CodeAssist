package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.List;
import java.util.Set;

/**
 * Represents a module's content root.
 * You can get existing entries with {@link ModuleRootModel#getContentEntries()} or
 * create a new one with {@link ModifiableRootModel#addContentEntry(VirtualFile)}. Note that methods which change the state can be called
 * only on instances of {@link ContentEntry} obtained from {@link ModifiableRootModel}. Calling these methods on instances obtained from
 * {@code ModuleRootManager.getInstance(module).getContentEntries()} may lead to failed assertion at runtime.
 *
 * @see ModuleRootModel#getContentEntries()
 * @see ModifiableRootModel#addContentEntry(VirtualFile)
 */
public interface ContentEntry extends Synthetic {
  /**
   * Returns the root file or directory for the content root, if it is valid.
   *
   * @return the content root file or directory, or null if content entry is invalid.
   */
  @Nullable
  VirtualFile getFile();

  /**
   * Returns the URL of content root.
   * To validate returned roots, use
   * <code>{@link com.intellij.openapi.vfs.VirtualFileManager#findFileByUrl(String)}</code>
   *
   * @return URL of content root, that should never be null.
   */
  @NonNull
  String getUrl();

  /**
   * Returns the list of source roots under this content root.
   *
   * @return array of this {@code ContentEntry} {@link SourceFolder}s
   */
  SourceFolder [] getSourceFolders();

//  /**
//   * @param rootType type of accepted source roots
//   * @return list of source roots of the specified type containing in this content root
//   */
//  @NonNull
//  List<SourceFolder> getSourceFolders(@NonNull JpsModuleSourceRootType<?> rootType);
//
//  /**
//   *
//   * @param rootTypes types of accepted source roots
//   * @return list of source roots of the specified types containing in this content root
//   */
//  @NonNull
//  List<SourceFolder> getSourceFolders(@NonNull Set<? extends JpsModuleSourceRootType<?>> rootTypes);

  /**
   * Returns the list of files and directories for valid source roots under this content root.
   *
   * @return array of all valid source roots.
   */
  VirtualFile[] getSourceFolderFiles();

  /**
   * Returns the list of excluded roots configured under this content root. The result doesn't include synthetic excludes like the module output.
   *
   * @return array of this {@code ContentEntry} {@link ExcludeFolder}s
   */
  @NonNull
  ExcludeFolder[] getExcludeFolders();

  /**
   * @return list of URLs for all excluded roots under this content root including synthetic excludes like the module output
   */
  @NonNull
  List<String> getExcludeFolderUrls();

  /**
   * Returns the list of files and directories for valid excluded roots under this content root.
   *
   * @return array of all valid exclude roots including synthetic excludes like the module output
   */
  @NonNull
  VirtualFile[] getExcludeFolderFiles();

  /**
   * Adds a source or test source root under the content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param file         the file or directory to add as a source root.
   * @param isTestSource true if the file or directory is added as a test source root.
   * @return the object representing the added root.
   */
  @NonNull
  SourceFolder addSourceFolder(@NonNull VirtualFile file, boolean isTestSource);

  /**
   * Adds a source or test source root with the specified package prefix under the content root. This method may be called only on an
   * instance obtained from {@link ModifiableRootModel}.
   *
   * @param file          the file or directory to add as a source root.
   * @param isTestSource  true if the file or directory is added as a test source root.
   * @param packagePrefix the package prefix for the root to add, or an empty string if no
   *                      package prefix is required.
   * @return the object representing the added root.
   */
  @NonNull
  SourceFolder addSourceFolder(@NonNull VirtualFile file, boolean isTestSource, @NonNull String packagePrefix);

//  /**
//   * Adds a source root of the given type with the given properties. This method may be called only on an instance obtained from
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull VirtualFile file,
//                                                               @NonNull JpsModuleSourceRootType<P> type,
//                                                               @NonNull P properties);
//
//  /**
//   * Adds a source root of the given type with the default properties. This method may be called only on an instance obtained from
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement>
//  SourceFolder addSourceFolder(@NonNull VirtualFile file, @NonNull JpsModuleSourceRootType<P> type);

  /**
   * Adds a source or test source root under the content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param url          the file or directory url to add as a source root.
   * @param isTestSource true if the file or directory is added as a test source root.
   * @return the object representing the added root.
   */
  @NonNull
  SourceFolder addSourceFolder(@NonNull String url, boolean isTestSource);

//  /**
//   * Adds a source root of the given type with the default properties. This method may be called only on an instance obtained from
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull String url, @NonNull JpsModuleSourceRootType<P> type);
//  /**
//   * Adds a source root of the given type with the default properties. This method may be called only on an instance obtained from
//   * Also method defines an external source
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull String url,
//                                                               @NonNull JpsModuleSourceRootType<P> type,
//                                                               @NonNull ProjectModelExternalSource externalSource);
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull String url,
//                                                               @NonNull JpsModuleSourceRootType<P> type,
//                                                               boolean useSourceOfContentRoot);

//  /**
//   * Adds a source root of the given type with given properties. This method may be called only on an instance obtained from
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull String url,
//                                                               @NonNull JpsModuleSourceRootType<P> type,
//                                                               @NonNull P properties);
//
//  /**
//   * Adds a source root of the given type with given properties. This method may be called only on an instance obtained from
//   * Also method defines an external source
//   * {@link ModifiableRootModel}.
//   */
//  @NonNull <P extends JpsElement> SourceFolder addSourceFolder(@NonNull String url,
//                                                               @NonNull JpsModuleSourceRootType<P> type,
//                                                               @NonNull P properties,
//                                                               @Nullable ProjectModelExternalSource externalSource);

  /**
   * Removes a source or test source root from this content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param sourceFolder the source root to remove (must belong to this content root).
   */
  void removeSourceFolder(@NonNull SourceFolder sourceFolder);

  /**
   * Removes all source roots. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   */
  void clearSourceFolders();

  /**
   * Adds an exclude root under the content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param file the file or directory to add as an exclude root.
   * @return the object representing the added root.
   */
  @NonNull
  ExcludeFolder addExcludeFolder(@NonNull VirtualFile file);

  /**
   * Adds an exclude root under the content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param url the file or directory url to add as an exclude root.
   * @return the object representing the added root.
   */
  @NonNull
  ExcludeFolder addExcludeFolder(@NonNull String url);
//  ExcludeFolder addExcludeFolder(@NonNull String url, ProjectModelExternalSource source);

  /**
   * Removes an exclude root from this content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param excludeFolder the exclude root to remove (must belong to this content root).
   */
  void removeExcludeFolder(@NonNull ExcludeFolder excludeFolder);

  /**
   * Removes an exclude root from this content root. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   *
   * @param url url of the exclude root
   * @return {@code true} if the exclude root was removed
   */
  boolean removeExcludeFolder(@NonNull String url);

  /**
   * Removes all excluded folders. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   */
  void clearExcludeFolders();

  /**
   * Returns patterns for names of files which should be excluded from this content root. If name of a file under this content root matches
   * any of the patterns it'll be excluded from the module, if name of a directory matches any of the patterns the directory and all of its
   * contents will be excluded. '?' and '*' wildcards are supported.
   */
  @NonNull
  List<String> getExcludePatterns();

  /**
   * Adds a pattern for names of files which should be excluded. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   */
  void addExcludePattern(@NonNull String pattern);

  /**
   * Removes a pattern for names of files which should be excluded. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   */
  void removeExcludePattern(@NonNull String pattern);

  /**
   * Sets patterns for names of files which should be excluded. This method may be called only on an instance obtained from {@link ModifiableRootModel}.
   */
  void setExcludePatterns(@NonNull List<String> patterns);

  @NonNull
  ModuleRootModel getRootModel();
}