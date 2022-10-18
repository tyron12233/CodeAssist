package com.tyron.completion.xml.v2.project;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128OutputStream;
import com.tyron.completion.xml.v2.aar.AarResourceRepository;
import com.tyron.completion.xml.v2.base.BasicFileResourceItem;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.completion.xml.v2.base.ResourceSourceFile;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.io.File;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link BasicFileResourceItem} plus the virtual file it is associated with.
 */
public class VfsFileResourceItem extends BasicFileResourceItem {
  @Nullable private final File myVirtualFile;

  /**
   * Initializes the resource.
   *
   * @param type          the type of the resource
   * @param name          the name of the resource
   * @param configuration the configuration the resource belongs to
   * @param visibility    the visibility of the resource
   * @param relativePath  defines location of the resource. Exact semantics of the path may vary depending on the resource repository
   */
  public VfsFileResourceItem(@NotNull ResourceType type,
                             @NotNull String name,
                             @NotNull RepositoryConfiguration configuration,
                             @NotNull ResourceVisibility visibility,
                             @NotNull String relativePath) {
    this(type, name, configuration, visibility, relativePath,
         new File(((ResourceFolderRepository) configuration.getRepository()).getResourceDir(), relativePath));
  }
  /**
   * Initializes the resource.
   *
   * @param type          the type of the resource
   * @param name          the name of the resource
   * @param configuration the configuration the resource belongs to
   * @param visibility    the visibility of the resource
   * @param relativePath  defines location of the resource. Exact semantics of the path may vary depending on the resource repository
   * @param virtualFile   the virtual file associated with the resource, or null of the resource is out of date
   */
  public VfsFileResourceItem(@NotNull ResourceType type,
                             @NotNull String name,
                             @NotNull RepositoryConfiguration configuration,
                             @NotNull ResourceVisibility visibility,
                             @NotNull String relativePath,
                             @Nullable File virtualFile) {
    super(type, name, configuration, visibility, relativePath);
    myVirtualFile = virtualFile;
  }

  @Override
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    super.serialize(stream, configIndexes, sourceFileIndexes, namespaceResolverIndexes);
    stream.write(FileTimeStampLengthHasher.hash(myVirtualFile));
  }

  @Override
  public boolean equals(@Nullable Object obj) {
      if (this == obj) {
          return true;
      }
      if (!super.equals(obj)) {
          return false;
      }
    VfsFileResourceItem other = (VfsFileResourceItem)obj;
    return isValid() == other.isValid();
  }

  @Nullable
  public File getVirtualFile() {
    return myVirtualFile;
  }

  public boolean isValid() {
    return myVirtualFile != null;
  }
}