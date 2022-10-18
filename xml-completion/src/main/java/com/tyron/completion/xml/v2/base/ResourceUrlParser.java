package com.tyron.completion.xml.v2.base;

import com.android.SdkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parser of resource URLs. Unlike {@link com.android.resources.ResourceUrl}, this class is resilient to URL syntax
 * errors doesn't create any GC overhead.
 */
public final class ResourceUrlParser {
  @NotNull private String resourceUrl = "";
  private int colonPos;
  private int slashPos;
  private int typeStart;
  private int namespacePrefixStart;
  private int nameStart;

  /**
   * Parses resource URL and sets the fields of this object to point to different parts of the URL.
   *
   * @param resourceUrl the resource URL to parse
   */
  public void parseResourceUrl(@NotNull String resourceUrl) {
    this.resourceUrl = resourceUrl;
    colonPos = -1;
    slashPos = -1;
    typeStart = -1;
    namespacePrefixStart = -1;

    int prefixEnd;
    if (resourceUrl.startsWith(SdkConstants.PREFIX_RESOURCE_REF)) {
      if (resourceUrl.startsWith("@+")) {
        prefixEnd = 2;
      } else {
        prefixEnd = 1;
      }
    } else if (resourceUrl.startsWith(SdkConstants.PREFIX_THEME_REF)) {
      prefixEnd = 1;
    } else {
      prefixEnd = 0;
    }
    if (resourceUrl.startsWith("*", prefixEnd)) {
      prefixEnd++;
    }

    int len = resourceUrl.length();
    int start = prefixEnd;
    loop: for (int i = prefixEnd; i < len; i++) {
      char c = resourceUrl.charAt(i);
      switch (c) {
        case '/':
          if (slashPos < 0) {
            slashPos = i;
            typeStart = start;
            start = i + 1;
            if (colonPos >= 0) {
              break loop;
            }
          }
          break;

        case ':':
          if (colonPos < 0) {
            colonPos = i;
            namespacePrefixStart = start;
            start = i + 1;
            if (slashPos >= 0) {
              break loop;
            }
          }
          break;
      }
    }
    nameStart = start;
  }

  /**
   * Returns the namespace prefix of the resource URL, or null if the URL doesn't contain a prefix.
   */
  @Nullable
  public String getNamespacePrefix() {
    return colonPos >= 0 ? resourceUrl.substring(namespacePrefixStart, colonPos) : null;
  }

  /**
   * Returns the type of the resource URL, or null if the URL don't contain a type.
   */
  @Nullable
  public String getType() {
    return slashPos >= 0 ? resourceUrl.substring(typeStart, slashPos) : null;
  }

  /**
   * Returns the name part of the resource URL.
   */
  @NotNull
  public String getName() {
    return resourceUrl.substring(nameStart);
  }

  /**
   * Returns the qualified name of the resource without any prefix or type.
   */
  @NotNull
  public String getQualifiedName() {
    if (colonPos < 0) {
      return getName();
    }
    if (nameStart == colonPos + 1) {
      return resourceUrl.substring(namespacePrefixStart);
    }
    return resourceUrl.substring(namespacePrefixStart, colonPos + 1) + getName();
  }

  /**
   * Checks if the resource URL has the given type.
   */
  public boolean hasType(@NotNull String type) {
    if (slashPos < 0) {
      return false;
    }
    return slashPos == typeStart + type.length() && resourceUrl.startsWith(type, typeStart);
  }

  /**
   * Checks if the resource URL has the given namespace prefix.
   */
  public boolean hasNamespacePrefix(@NotNull String namespacePrefix) {
    if (colonPos < 0) {
      return false;
    }
    return colonPos == namespacePrefixStart + namespacePrefix.length() && resourceUrl.startsWith(namespacePrefix, namespacePrefixStart);
  }
}
