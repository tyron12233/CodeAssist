package com.tyron.completion.xml.v2.base;

import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.ide.common.util.PathString;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Repository of resources loaded from a file or a directory on disk.
 */
public interface LoadableResourceRepository extends SingleNamespaceResourceRepository {
  /**
   * Returns the name of the library, or null if this is not an AAR resource repository.
   */
  @Nullable
  String getLibraryName();

  /**
   * Returns the name of this resource repository to display in the UI.
   */
  @NotNull
  String getDisplayName();

  /**
   * Returns the file or directory this resource repository was loaded from. Resource repositories loaded from
   * the same file or directory with different file filtering options have the same origin.
   */
  @NotNull
  Path getOrigin();

  /**
   * Produces a string to be returned by the {@link BasicFileResourceItem#getValue()} method.
   * The string represents an URL in one of the following formats:
   * <ul>
   *  <li>file URL, e.g. "file:///foo/bar/res/layout/my_layout.xml"</li>
   *  <li>URL of a zipped element inside the res.apk file, e.g. "apk:///foo/bar/res.apk!/res/layout/my_layout.xml"</li>
   * </ul>
   *
   * @param relativeResourcePath the relative path of a file resource
   * @return the URL pointing to the file resource
   */
  @NotNull
  String getResourceUrl(@NotNull String relativeResourcePath);

  /**
   * Produces a {@link PathString} to be returned by the {@link BasicResourceItem#getSource()} method.
   *
   * @param relativeResourcePath the relative path of the file the resource was created from
   * @param forFileResource true is the resource is a file resource, false if it is a value resource
   * @return the PathString to be returned by the {@link BasicResourceItem#getSource()} method
   */
  @NotNull
  PathString getSourceFile(@NotNull String relativeResourcePath, boolean forFileResource);

  /**
   * Produces a {@link PathString} to be returned by the {@link BasicResourceItem#getOriginalSource()} method.
   *
   * @param relativeResourcePath the relative path of the file the resource was created from
   * @param forFileResource true is the resource is a file resource, false if it is a value resource
   * @return the PathString to be returned by the {@link BasicResourceItem#getOriginalSource()} method
   */
  @Nullable
  default PathString getOriginalSourceFile(@NotNull String relativeResourcePath, boolean forFileResource) {
    return getSourceFile(relativeResourcePath, forFileResource);
  }

  /**
   * Creates a {@link ResourceSourceFile} by reading its contents from the given stream.
   *
   * @param stream the stream to read data from
   * @param configurations the repository configurations to select from when creating the ResourceSourceFile
   * @return the created {@link ResourceSourceFile}
   */
  @NotNull
  default ResourceSourceFile deserializeResourceSourceFile(
      @NotNull Base128InputStream stream, @NotNull List<RepositoryConfiguration> configurations) throws IOException {
    return ResourceSourceFileImpl.deserialize(stream, configurations);
  }

  /**
   * Creates a {@link BasicFileResourceItem} by reading its contents from the given stream.
   *
   * @param stream the stream to read data from
   * @param resourceType the type of the resource
   * @param name the name of the resource
   * @param visibility the visibility of the resource
   * @param configurations the repository configurations to select from when creating the ResourceSourceFile
   * @return the created {@link BasicFileResourceItem}
   */
  @NotNull
  default BasicFileResourceItem deserializeFileResourceItem(
      @NotNull Base128InputStream stream,
      @NotNull ResourceType resourceType,
      @NotNull String name,
      @NotNull ResourceVisibility visibility,
      @NotNull List<RepositoryConfiguration> configurations) throws IOException {
    return BasicFileResourceItem.deserialize(stream, resourceType, name, visibility, configurations);
  }

  boolean containsUserDefinedResources();
}