package com.tyron.completion.xml.v2.base;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128InputStream.StreamFormatException;
import com.android.utils.Base128OutputStream;
import com.android.utils.HashCodes;
import com.google.common.base.MoreObjects;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base class for implementations of the {@link BasicResourceItem} interface. */
public abstract class BasicResourceItemBase implements BasicResourceItem, ResourceValue {
  @NotNull private final String myName;
  // Store enums as their ordinals in byte form to minimize memory footprint.
  private final byte myTypeOrdinal;
  private final byte myVisibilityOrdinal;

  BasicResourceItemBase(@NotNull ResourceType type, @NotNull String name, @NotNull ResourceVisibility visibility) {
    myName = name;
    myTypeOrdinal = (byte)type.ordinal();
    myVisibilityOrdinal = (byte)visibility.ordinal();
  }

  @Override
  @NotNull
  public final ResourceType getType() {
    return getResourceType();
  }

  @Override
  @NotNull
  public ResourceNamespace getNamespace() {
    return getRepository().getNamespace();
  }

  @Override
  @NotNull
  public final String getName() {
    return myName;
  }

  @Override
  @Nullable
  public final String getLibraryName() {
    return getRepository().getLibraryName();
  }

  @Override
  @NotNull
  public final ResourceType getResourceType() {
    return ResourceType.values()[myTypeOrdinal];
  }

  @Override
  @NotNull
  public final ResourceVisibility getVisibility() {
    return ResourceVisibility.values()[myVisibilityOrdinal];
  }

  @Override
  @NotNull
  public final ResourceReference getReferenceToSelf() {
    return asReference();
  }

  @Override
  @NotNull
  public final ResourceValue getResourceValue() {
    return this;
  }

  @Override
  public final boolean isUserDefined() {
    return getRepository().containsUserDefinedResources();
  }

  @Override
  public final boolean isFramework() {
    return getNamespace().equals(ResourceNamespace.ANDROID);
  }

  @Override
  @NotNull
  public final ResourceReference asReference() {
    return new ResourceReference(getNamespace(), getResourceType(), myName);
  }

  /**
   * Returns the repository this resource belongs to.
   * <p>
   * Framework resource items may move between repositories with the same origin.
   * @see RepositoryConfiguration#transferOwnershipTo(LoadableResourceRepository)
   */
  @Override
  @NotNull
  public final LoadableResourceRepository getRepository() {
    return getRepositoryConfiguration().getRepository();
  }

  @Override
  @NotNull
  public final FolderConfiguration getConfiguration() {
    return getRepositoryConfiguration().getFolderConfiguration();
  }

  @NotNull
  public abstract RepositoryConfiguration getRepositoryConfiguration();

    @Override
  @NotNull
  public final String getKey() {
    String qualifiers = getConfiguration().getQualifierString();
    if (!qualifiers.isEmpty()) {
      return getType().getName() + '-' + qualifiers + '/' + getName();
    }

    return getType().getName() + '/' + getName();
  }

  @Override
  public final void setValue(@Nullable String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    BasicResourceItemBase other = (BasicResourceItemBase) obj;
    return myTypeOrdinal == other.myTypeOrdinal
        && myName.equals(other.myName)
        && myVisibilityOrdinal == other.myVisibilityOrdinal;
  }

  @Override
  public int hashCode() {
    // The myVisibilityOrdinal field is intentionally not included in hash code because having two resource items
    // differing only by visibility in the same hash table is extremely unlikely.
    return HashCodes.mix(myTypeOrdinal, myName.hashCode());
  }

  @Override
  @NotNull
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("namespace", getNamespace())
                      .add("type", getResourceType())
                      .add("name", getName())
                      .add("value", getValue())
                      .toString();
  }

  /**
   * Serializes the resource item to the given stream.
   */
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    stream.writeInt((myTypeOrdinal << 1) + (isFileBased() ? 1 : 0));
    stream.writeString(myName);
    stream.writeInt(myVisibilityOrdinal);
  }

  /**
   * Creates a resource item by reading its contents from the given stream.
   */
  @NotNull
  public static BasicResourceItemBase deserialize(@NotNull Base128InputStream stream,
                                                  @NotNull List<RepositoryConfiguration> configurations,
                                                  @NotNull List<ResourceSourceFile> sourceFiles,
                                                  @NotNull List<ResourceNamespace.Resolver> namespaceResolvers) throws IOException {
    assert !configurations.isEmpty();
    int encodedType = stream.readInt();
    boolean isFileBased = (encodedType & 0x1) != 0;
    ResourceType resourceType = ResourceType.values()[encodedType >>> 1];
    String name = stream.readString();
    if (name == null) {
      throw StreamFormatException.invalidFormat();
    }
    ResourceVisibility visibility = ResourceVisibility.values()[stream.readInt()];

    if (isFileBased) {
      LoadableResourceRepository repository = configurations.get(0).getRepository();
      return repository.deserializeFileResourceItem(stream, resourceType, name, visibility, configurations);
    }

    return BasicValueResourceItemBase.deserialize(stream, resourceType, name, visibility, configurations, sourceFiles, namespaceResolvers);
  }
}