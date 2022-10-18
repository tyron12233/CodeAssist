package com.tyron.completion.xml.v2.base;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128OutputStream;
import com.android.utils.HashCodes;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resource value representing a reference to an attr resource, but potentially with its own description
 * and group name. Unlike {@link BasicAttrResourceItem}, does not contain formats and enum or flag information.
 */
public final class BasicAttrReference extends BasicValueResourceItemBase implements AttrResourceValue {
  @NotNull private final ResourceNamespace myNamespace;
  @Nullable private final String myDescription;
  @Nullable private final String myGroupName;

  /**
   * Initializes the attr reference.
   *
   * @param namespace the namespace of the attr resource
   * @param name the name of the resource
   * @param sourceFile the source file containing definition of the resource
   * @param description the description of the attr resource, if available
   * @param groupName the name of the attr group, if available
   */
  public BasicAttrReference(@NotNull ResourceNamespace namespace,
                            @NotNull String name,
                            @NotNull ResourceSourceFile sourceFile,
                            @NotNull ResourceVisibility visibility,
                            @Nullable String description,
                            @Nullable String groupName) {
    super(ResourceType.ATTR, name, sourceFile, visibility);
    myNamespace = namespace;
    myDescription = description;
    myGroupName = groupName;
  }

  @Override
  @NotNull
  public ResourceNamespace getNamespace() {
    return myNamespace;
  }

  @Override
  @NotNull
  public final Set<AttributeFormat> getFormats() {
    return Collections.emptySet();
  }

  @Override
  @NotNull
  public final Map<String, Integer> getAttributeValues() {
    return Collections.emptyMap();
  }

  @Override
  @Nullable
  public final String getValueDescription(@NotNull String valueName) {
    return null;
  }

  @Override
  @Nullable
  public final String getDescription() {
    return myDescription;
  }

  @Override
  @Nullable
  public final String getGroupName() {
    return myGroupName;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
      if (this == obj) {
          return true;
      }
      if (!super.equals(obj)) {
          return false;
      }
    BasicAttrReference other = (BasicAttrReference) obj;
    return myNamespace.equals(other.myNamespace) &&
        Objects.equals(myDescription, other.myDescription) &&
        Objects.equals(myGroupName, other.myGroupName);
  }

  @Override
  public int hashCode() {
    // myGroupName is not included in hash code intentionally since it doesn't improve quality of hashing.
    return HashCodes.mix(super.hashCode(), myNamespace.hashCode(), Objects.hashCode(myDescription));
  }

  @Override
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    super.serialize(stream, configIndexes, sourceFileIndexes, namespaceResolverIndexes);
    BasicAttrResourceItem.serializeAttrValue(this, getRepository().getNamespace(), stream);
  }
}
