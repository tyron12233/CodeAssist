package com.tyron.completion.xml.v2.base;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128OutputStream;
import com.android.utils.HashCodes;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resource item representing a value resource, e.g. a string or a color.
 */
public class BasicValueResourceItem extends BasicValueResourceItemBase {
  @Nullable private final String myValue;

  /**
   * Initializes the resource.
   *
   * @param type the type of the resource
   * @param name the name of the resource
   * @param sourceFile the source file containing definition of the resource
   * @param visibility the visibility of the resource
   * @param value the value associated with the resource
   */
  public BasicValueResourceItem(@NotNull ResourceType type,
                                @NotNull String name,
                                @NotNull ResourceSourceFile sourceFile,
                                @NotNull ResourceVisibility visibility,
                                @Nullable String value) {
    super(type, name, sourceFile, visibility);
    myValue = value;
  }

  @Override
  @Nullable
  public String getValue() {
    return myValue;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
      if (this == obj) {
          return true;
      }
      if (!super.equals(obj)) {
          return false;
      }
    BasicValueResourceItem other = (BasicValueResourceItem) obj;
    return Objects.equals(myValue, other.myValue);
  }

  @Override
  public int hashCode() {
    return HashCodes.mix(super.hashCode(), Objects.hashCode(myValue));
  }

  @Override
  public void serialize(@NotNull Base128OutputStream stream,
                        @NotNull Object2IntMap<String> configIndexes,
                        @NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                        @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) throws IOException {
    super.serialize(stream, configIndexes, sourceFileIndexes, namespaceResolverIndexes);
    stream.writeString(myValue);
    String rawXmlValue = getRawXmlValue();
    stream.writeString(Objects.equals(rawXmlValue, myValue) ? null : rawXmlValue);
  }

  /**
   * Creates a BasicValueResourceItem by reading its contents from the given stream.
   */
  @NotNull
  static BasicValueResourceItem deserialize(@NotNull Base128InputStream stream,
                                            @NotNull ResourceType resourceType,
                                            @NotNull String name,
                                            @NotNull ResourceVisibility visibility,
                                            @NotNull ResourceSourceFile sourceFile,
                                            @NotNull ResourceNamespace.Resolver resolver) throws IOException {
    String value = stream.readString();
    String rawXmlValue = stream.readString();
    BasicValueResourceItem item = rawXmlValue == null ?
                                  new BasicValueResourceItem(resourceType, name, sourceFile, visibility, value) :
                                  new BasicTextValueResourceItem(resourceType, name, sourceFile, visibility, value, rawXmlValue);
    item.setNamespaceResolver(resolver);
    return item;
  }
}
