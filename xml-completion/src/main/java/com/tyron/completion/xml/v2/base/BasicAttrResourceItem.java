package com.tyron.completion.xml.v2.base;

import static com.android.SdkConstants.URI_DOMAIN_PREFIX;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128InputStream.StreamFormatException;
import com.android.utils.Base128OutputStream;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resource item representing an attr resource.
 */
public class BasicAttrResourceItem extends BasicValueResourceItemBase implements AttrResourceValue {
  @NotNull private Set<AttributeFormat> myFormats;
  /** The keys are enum or flag names, the values are corresponding numeric values. */
  @NotNull private final Map<String, Integer> myValueMap;
  /** The keys are enum or flag names, the values are the value descriptions. */
  @NotNull private final Map<String, String> myValueDescriptionMap;
  @Nullable private final String myDescription;
  @Nullable private final String myGroupName;

  /**
   * Initializes the resource.
   *
   * @param name the name of the resource
   * @param sourceFile the source file containing definition of the resource
   * @param visibility the visibility of the resource
   * @param description the description of the attr resource, if available
   * @param groupName the name of the attr group, if available
   * @param formats the allowed attribute formats
   * @param valueMap the enum or flag integer values keyed by the value names. Some of the values in the
   *     map may be null. The map must contain the names of all declared values, even the ones that don't
   *     have corresponding numeric values.
   * @param valueDescriptionMap the enum or flag value descriptions keyed by the value names
   */
  public BasicAttrResourceItem(@NotNull String name,
                               @NotNull ResourceSourceFile sourceFile,
                               @NotNull ResourceVisibility visibility,
                               @Nullable String description,
                               @Nullable String groupName,
                               @NotNull Set<AttributeFormat> formats,
                               @NotNull Map<String, Integer> valueMap,
                               @NotNull Map<String, String> valueDescriptionMap) {
    super(ResourceType.ATTR, name, sourceFile, visibility);
    myDescription = description;
    myGroupName = groupName;
    myFormats = ImmutableSet.copyOf(formats);
    // Cannot use ImmutableMap.copyOf() since valueMap may contain null values.
    myValueMap = valueMap.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(valueMap);
    myValueDescriptionMap = valueDescriptionMap.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(valueDescriptionMap);
  }

  @Override
  @NotNull
  public final Set<AttributeFormat> getFormats() {
    return myFormats;
  }

  /**
   * Replaces the set of the allowed attribute formats. Intended to be called only by the resource repository code.
   *
   * @param formats the new set of the allowed attribute formats
   */
  public final void setFormats(@NotNull Set<AttributeFormat> formats) {
    myFormats = ImmutableSet.copyOf(formats);
  }

  @Override
  @NotNull
  public final Map<String, Integer> getAttributeValues() {
    return myValueMap;
  }

  @Override
  @Nullable
  public final String getValueDescription(@NotNull String valueName) {
    return myValueDescriptionMap.get(valueName);
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
    BasicAttrResourceItem other = (BasicAttrResourceItem) obj;
    return Objects.equals(myDescription, other.myDescription) &&
           Objects.equals(myGroupName, other.myGroupName) &&
           myFormats.equals(other.myFormats) &&
           myValueMap.equals(other.myValueMap) &&
           myValueDescriptionMap.equals(other.myValueDescriptionMap);
  }

  /**
   * Creates and returns an {@link BasicAttrReference} pointing to this attribute.
   */
  @NotNull
  public BasicAttrReference createReference() {
    BasicAttrReference attrReference =
        new BasicAttrReference(getNamespace(), getName(), getSourceFile(), getVisibility(), myDescription, myGroupName);
    attrReference.setNamespaceResolver(getNamespaceResolver());
    return attrReference;
  }

  @Override
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    super.serialize(stream, configIndexes, sourceFileIndexes, namespaceResolverIndexes);
    serializeAttrValue(this, getRepository().getNamespace(), stream);
  }

  static void serializeAttrValue(@NotNull AttrResourceValue attr, @NotNull ResourceNamespace defaultNamespace,
                                 @NotNull Base128OutputStream stream) throws IOException {
    ResourceNamespace namespace = attr.getNamespace();
    String namespaceSuffix = namespace.equals(defaultNamespace) ?
                             null : namespace.getXmlNamespaceUri().substring(URI_DOMAIN_PREFIX.length());
    stream.writeString(namespaceSuffix);

    stream.writeString(attr.getDescription());
    stream.writeString(attr.getGroupName());

    int formatMask = 0;
    for (AttributeFormat format : attr.getFormats()) {
      formatMask |= 1 << format.ordinal();
    }
    stream.writeInt(formatMask);

    Map<String, Integer> attributeValues = attr.getAttributeValues();
    stream.writeInt(attributeValues.size());
    for (Map.Entry<String, Integer> entry : attributeValues.entrySet()) {
      String name = entry.getKey();
      stream.writeString(name);
      Integer value = entry.getValue();
      int v = value == null ? Integer.MIN_VALUE : value + 1; // Use value + 1 to reduce length of encoded -1 value.
      stream.writeInt(v);
      String description = attr.getValueDescription(name);
      stream.writeString(description);
    }
  }

  /**
   * Creates a BasicAttrResourceItem by reading its contents from the given stream.
   */
  @NotNull
  static BasicValueResourceItemBase deserialize(@NotNull Base128InputStream stream,
                                                @NotNull String name,
                                                @NotNull ResourceVisibility visibility,
                                                @NotNull ResourceSourceFile sourceFile,
                                                @NotNull ResourceNamespace.Resolver resolver) throws IOException {
    String namespaceSuffix = stream.readString();
    String description = stream.readString();
    String groupName = stream.readString();

    int formatMask = stream.readInt();
    Set<AttributeFormat> formats = EnumSet.noneOf(AttributeFormat.class);
    AttributeFormat[] attributeFormatValues = AttributeFormat.values();
    for (int ordinal = 0; ordinal < attributeFormatValues.length && formatMask != 0; ordinal++, formatMask >>>= 1) {
      if ((formatMask & 0x1) != 0) {
        formats.add(attributeFormatValues[ordinal]);
      }
    }
    int n = stream.readInt();
    Map<String, Integer> valueMap = n == 0 ? Collections.emptyMap() : Maps.newHashMapWithExpectedSize(n);
    Map<String, String> descriptionMap = n == 0 ? Collections.emptyMap() : Maps.newHashMapWithExpectedSize(n);
    for (int i = 0; i < n; i++) {
      String valueName = stream.readString();
      int value = stream.readInt();
      if (value != Integer.MIN_VALUE) {
        valueMap.put(valueName, value - 1);
      }
      String valueDescription = stream.readString();
      if (valueDescription != null) {
        descriptionMap.put(valueName, valueDescription);
      }
    }
    BasicValueResourceItemBase item;
    if (formats.isEmpty() && valueMap.isEmpty()) {
      ResourceNamespace namespace = namespaceSuffix == null ?
                                    sourceFile.getRepository().getNamespace() :
                                    ResourceNamespace.fromNamespaceUri(URI_DOMAIN_PREFIX + namespaceSuffix);
      if (namespace == null) {
        throw StreamFormatException.invalidFormat();
      }
      item = new BasicAttrReference(namespace, name, sourceFile, visibility, description, groupName);
    }
    else if (namespaceSuffix == null) {
      item = new BasicAttrResourceItem(name, sourceFile, visibility, description, groupName, formats, valueMap, descriptionMap);
    }
    else {
      ResourceNamespace namespace = ResourceNamespace.fromNamespaceUri(URI_DOMAIN_PREFIX + namespaceSuffix);
      if (namespace == null) {
        throw StreamFormatException.invalidFormat();
      }
      item = new BasicForeignAttrResourceItem(namespace, name, sourceFile, description, groupName, formats, valueMap, descriptionMap);
    }
    item.setNamespaceResolver(resolver);
    return item;
  }
}
