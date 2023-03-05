package com.tyron.completion.xml.v2.base;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.util.PathString;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128OutputStream;
import com.android.utils.HashCodes;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base class for value resource items. */
public abstract class BasicValueResourceItemBase extends BasicResourceItemBase {
  @NotNull private final ResourceSourceFile mySourceFile;
  @NotNull private ResourceNamespace.Resolver myNamespaceResolver = ResourceNamespace.Resolver.EMPTY_RESOLVER;

  /**
   * Initializes the resource.
   *
   * @param type the type of the resource
   * @param name the name of the resource
   * @param sourceFile the source file containing definition of the resource
   * @param visibility the visibility of the resource
   */
  public BasicValueResourceItemBase(@NotNull ResourceType type,
                                    @NotNull String name,
                                    @NotNull ResourceSourceFile sourceFile,
                                    @NotNull ResourceVisibility visibility) {
    super(type, name, visibility);
    mySourceFile = sourceFile;
  }

  @Override
  @Nullable
  public String getValue() {
    return null;
  }

  @Override
  public final boolean isFileBased() {
    return false;
  }

  @Override
  @NotNull
  public final RepositoryConfiguration getRepositoryConfiguration() {
    return mySourceFile.getConfiguration();
  }

  @Override
  @NotNull
  public final ResourceNamespace.Resolver getNamespaceResolver() {
    return myNamespaceResolver;
  }

  public final void setNamespaceResolver(@NotNull ResourceNamespace.Resolver resolver) {
    myNamespaceResolver = resolver;
  }

  @Override
  @Nullable
  public final PathString getSource() {
    return getOriginalSource();
  }

  @Override
  @Nullable
  public final PathString getOriginalSource() {
    String sourcePath = mySourceFile.getRelativePath();
    return sourcePath == null ? null : getRepository().getOriginalSourceFile(sourcePath, false);
  }

  @NotNull
  public final ResourceSourceFile getSourceFile() {
    return mySourceFile;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
      if (this == obj) {
          return true;
      }
      if (!super.equals(obj)) {
          return false;
      }
    BasicValueResourceItemBase other = (BasicValueResourceItemBase)obj;
    return Objects.equals(mySourceFile, other.mySourceFile);
  }

  @Override
  public int hashCode() {
    return HashCodes.mix(super.hashCode(), Objects.hashCode(mySourceFile));
  }

  @Override
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    super.serialize(stream, configIndexes, sourceFileIndexes, namespaceResolverIndexes);
    int index = sourceFileIndexes.getInt(mySourceFile);
    assert index >= 0;
    stream.writeInt(index);
    index = namespaceResolverIndexes.getInt(myNamespaceResolver);
    assert index >= 0;
    stream.writeInt(index);
  }

  /**
   * Creates a resource item by reading its contents from the given stream.
   */
  @NotNull
  static BasicValueResourceItemBase deserialize(@NotNull Base128InputStream stream,
                                                @NotNull ResourceType resourceType,
                                                @NotNull String name,
                                                @NotNull ResourceVisibility visibility,
                                                @NotNull List<RepositoryConfiguration> configurations,
                                                @NotNull List<ResourceSourceFile> sourceFiles,
                                                @NotNull List<ResourceNamespace.Resolver> namespaceResolvers) throws IOException {
    ResourceSourceFile sourceFile = sourceFiles.get(stream.readInt());
    ResourceNamespace.Resolver resolver = namespaceResolvers.get(stream.readInt());

    switch (resourceType) {
      case ARRAY:
        return BasicArrayResourceItem.deserialize(stream, name, visibility, sourceFile, resolver);

      case ATTR:
        return BasicAttrResourceItem.deserialize(stream, name, visibility, sourceFile, resolver);

      case PLURALS:
        return BasicPluralsResourceItem.deserialize(stream, name, visibility, sourceFile, resolver);

      case STYLE:
        return BasicStyleResourceItem.deserialize(stream, name, visibility, sourceFile, resolver, namespaceResolvers);

      case STYLEABLE:
        return BasicStyleableResourceItem.deserialize(
            stream, name, visibility, sourceFile, resolver, configurations, sourceFiles, namespaceResolvers);

      default:
        return BasicValueResourceItem.deserialize(stream, resourceType, name, visibility, sourceFile, resolver);
    }
  }
}
