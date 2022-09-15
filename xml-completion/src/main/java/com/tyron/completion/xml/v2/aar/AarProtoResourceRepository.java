package com.tyron.completion.xml.v2.aar;

import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources;
import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.ide.common.rendering.api.DensityBasedResourceValue;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.StyleItemResourceValue;
import com.android.ide.common.rendering.api.StyleItemResourceValueImpl;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.ide.common.resources.AndroidManifestPackageNameUtils;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.util.PathString;
import com.android.resources.Arity;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.SdkUtils;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Table;
import com.google.protobuf.ByteString;
import com.tyron.completion.xml.v2.base.BasicArrayResourceItem;
import com.tyron.completion.xml.v2.base.BasicAttrReference;
import com.tyron.completion.xml.v2.base.BasicAttrResourceItem;
import com.tyron.completion.xml.v2.base.BasicDensityBasedFileResourceItem;
import com.tyron.completion.xml.v2.base.BasicFileResourceItem;
import com.tyron.completion.xml.v2.base.BasicPluralsResourceItem;
import com.tyron.completion.xml.v2.base.BasicResourceItem;
import com.tyron.completion.xml.v2.base.BasicStyleResourceItem;
import com.tyron.completion.xml.v2.base.BasicStyleableResourceItem;
import com.tyron.completion.xml.v2.base.BasicTextValueResourceItem;
import com.tyron.completion.xml.v2.base.BasicValueResourceItem;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.completion.xml.v2.base.RepositoryLoader;
import com.tyron.completion.xml.v2.base.ResourceSourceFile;
import com.tyron.completion.xml.v2.base.ResourceSourceFileImpl;
import com.tyron.completion.xml.v2.base.ResourceUrlParser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.util.BitUtil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.android.SdkConstants.DOT_XML;
import static com.android.utils.DecimalUtils.trimInsignificantZeros;
import static com.tyron.completion.xml.v2.base.RepositoryLoader.portableFileName;

/**
 * Repository of resources defined in an AAR file where resources are stored in protocol buffer format.
 * See https://developer.android.com/studio/projects/android-library.html.
 * See https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/Resources.proto
 */
public class AarProtoResourceRepository extends AbstractAarResourceRepository {
  /** Configuration filter that accepts all configurations. */
  protected static final Predicate<Configuration> TRIVIAL_CONFIG_FILTER = config -> true;
  /** Resource type filter that accepts all resource types. */
  protected static final Predicate<ResourceType> TRIVIAL_RESOURCE_TYPE_FILTER = type -> true;

  /** Protocol for accessing contents of .apk files. */
  @NotNull private static final String APK_PROTOCOL = "apk";
  /** The name of the res.apk ZIP entry containing value resources. */
  private static final String RESOURCE_TABLE_ENTRY = "resources.pb";

  private static final Logger LOG = Logger.getInstance(AarProtoResourceRepository.class);

  // The following constants represent the complex dimension encoding defined in
  // https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h
  private static final int COMPLEX_UNIT_MASK = 0xF;
  private static final String[] DIMEN_SUFFIXES = {"px", "dp", "sp", "pt", "in", "mm"};
  private static final String[] FRACTION_SUFFIXES = {"%", "%p"};
  private static final int COMPLEX_RADIX_SHIFT = 4;
  private static final int COMPLEX_RADIX_MASK = 0x3;
  /** Multiplication factors for 4 possible radixes. */
  private static final double[] RADIX_FACTORS = {1., 1. / (1 << 7), 1. / (1 << 15), 1. / (1 << 23)};
  // The signed mantissa is stored in the higher 24 bits of the value.
  private static final int COMPLEX_MANTISSA_SHIFT = 8;

  @NotNull protected final Path myResApkFile;
  /**
   * Common prefix of paths of all file resources. Used to compose resource paths returned by
   * the {@link BasicFileResourceItem#getSource()} method.
   */
  @NotNull private final String myResourcePathPrefix;
  /**
   * Common prefix of URLs of all file resources. Used to compose resource URLs returned by
   * the {@link BasicFileResourceItem#getValue()} method.
   */
  @NotNull private final String myResourceUrlPrefix;
  /**
   * Common prefix of source attachments. Used to compose file paths returned by
   * the {@link BasicResourceItem#getOriginalSource()} method.
   */
  @Nullable private final String mySourceAttachmentPrefix;

  protected AarProtoResourceRepository(@NotNull Loader loader, @Nullable String libraryName, @Nullable Path sourceJar) {
    super(loader.myNamespace, libraryName);
    myResApkFile = loader.myResApkFile;

    myResourcePathPrefix = myResApkFile.toString() + "!/";
    myResourceUrlPrefix = APK_PROTOCOL + "://" + portableFileName(myResApkFile.toString()) + "!/";

    mySourceAttachmentPrefix = sourceJar != null && loader.myPackageName != null ?
        sourceJar.toString() + "!/" + getPackageNamePrefix(loader.myPackageName) : null;
  }

  @Override
  @NotNull
  public Path getOrigin() {
    return myResApkFile;
  }

  @Override
  @Nullable
  public final String getPackageName() {
    return myNamespace.getPackageName();
  }

  /**
   * Creates a resource repository for an AAR file.
   *
   * @param resApkFile the res.apk file
   * @param libraryName the name of the library
   * @return the created resource repository
   */
  @NotNull
  public static AarProtoResourceRepository create(@NotNull Path resApkFile, @NotNull String libraryName) {
    Loader loader = new Loader(resApkFile, TRIVIAL_CONFIG_FILTER, TRIVIAL_RESOURCE_TYPE_FILTER);
    try {
      loader.readApkFile();
    } catch (IOException e) {
      LOG.error(e);
      // Return an empty repository.
      return new AarProtoResourceRepository(loader, libraryName, null);
    }

    // TODO: Make the source jar a parameter of this method and stop relying on a name convention here.
    Path sourceJar = getSourceJarPath(resApkFile);
    if (!Files.exists(sourceJar)) {
      sourceJar = null;
    }


    AarProtoResourceRepository repository = new AarProtoResourceRepository(loader, libraryName, sourceJar);
    loader.loadRepositoryContents(repository);
    return repository;
  }

  /**
   * Returns the path of the source JAR file given the path of res.apk. The name of the source jar is obtained
   * by replacing the ".apk" file name suffix with "-src.jar".
   */
  private static Path getSourceJarPath(@NotNull Path resApkFile) {
    String filename = resApkFile.getFileName().toString();
    int extensionPos = filename.lastIndexOf('.');
    if (extensionPos >= 0) {
      filename = filename.substring(0, extensionPos);
    }
    filename += "-src.jar";
    return resApkFile.resolveSibling(filename);
  }

  @Override
  @NotNull
  public final String getResourceUrl(@NotNull String relativeResourcePath) {
    return expandRelativeResourcePath(myResourceUrlPrefix, relativeResourcePath, true);
  }

  @Override
  @NotNull
  public final PathString getSourceFile(@NotNull String relativeResourcePath, boolean forFileResource) {
    return new PathString(APK_PROTOCOL, expandRelativeResourcePath(myResourcePathPrefix, relativeResourcePath, forFileResource));
  }

  /**
   * Converts a relative resource path to an absolute path or URL pointing inside res.apk by prepending a given
   * {@code prefix} to the path. If {@code relativeResourcePath} is a path inside res.apk, the prefix is simply
   * prepended to it. If {@code relativeResourcePath} is a path inside a source attachment JAR without a package
   * prefix, it is first converted to a path inside res.apk by removing the first, overlay number, segment. Then
   * the prefix is prepended to the converted path. Whether the path points inside res.apk or the source
   * attachment JAR is determined by result returned by the {@link #hasOverlaySegment(String, boolean)}.
   *
   * @param prefix the prefix to prepend
   * @param relativeResourcePath the relative path of a resource that may or may not start with an overlay number segment
   * @param forFileResource true is the resource is a file resource, false if it is a value resource
   * @return the converted path
   */
  private String expandRelativeResourcePath(@NotNull String prefix, @NotNull String relativeResourcePath, boolean forFileResource) {
    int offset = 0;
    if (hasOverlaySegment(relativeResourcePath, forFileResource)) {
      assert Character.isDigit(relativeResourcePath.charAt(0));
      // relativeResourcePath is the path of the original source that includes an overlay number as the first segment.
      // Skip the first segment to convert the source path to the path of proto XML.
      offset = relativeResourcePath.indexOf('/') + 1;
    }
    int prefixLength = prefix.length();
    int pathLength = relativeResourcePath.length();
    char[] result = new char[prefixLength + pathLength - offset];
    prefix.getChars(0, prefixLength, result, 0);
    relativeResourcePath.getChars(offset, pathLength, result, prefixLength);
    return new String(result);
  }

  /**
   * Checks if the given relative resource path is expected to contain an overlay segment or not.
   * The check is based on how resource items are created by the {@link Loader#createResourceItem} methods.
   *
   * @param relativeResourcePath the relative path of a resource that may or may not start with an overlay number segment
   * @param forFileResource true is the resource is a file resource, false if it is a value resource
   * @return true if the resource path is expected to contain an overlay segment
   */
  private boolean hasOverlaySegment(@NotNull String relativeResourcePath, boolean forFileResource) {
    return forFileResource && mySourceAttachmentPrefix != null && isXml(relativeResourcePath);
  }

  @Override
  @Nullable
  public final PathString getOriginalSourceFile(@NotNull String relativeResourcePath, boolean forFileResource) {
    if (isXml(relativeResourcePath)) {
      if (mySourceAttachmentPrefix == null) {
        return null;
      }
      return new PathString("jar", mySourceAttachmentPrefix + relativeResourcePath);
    }

    return getSourceFile(relativeResourcePath, forFileResource);
  }

  private static boolean isXml(@NotNull String filePath) {
    return SdkUtils.endsWithIgnoreCase(filePath, DOT_XML);
  }

  @NotNull
  private static String getPackageNamePrefix(@NotNull String packageName) {
    return packageName.replace('.', '/') + '/';
  }

  // For debugging only.
  @Override
  public String toString() {
    return getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(this)) + " for " + myResApkFile;
  }

  protected static class Loader {
    @NotNull private final Path myResApkFile;
    @NotNull private final Predicate<Configuration> myConfigFilter;
    @NotNull private final Predicate<ResourceType> myResourceTypeFilter;
    @NotNull private final ResourceUrlParser myUrlParser = new ResourceUrlParser();
    @NotNull private final ListMultimap<String, BasicStyleableResourceItem> myStyleables = ArrayListMultimap.create();
    @NotNull private final Table<String, Configuration, ResourceSourceFile> mySourceFileCache = HashBasedTable.create();
    @Nullable private Resources.ResourceTable myResourceTableMsg;
    @Nullable private String myPackageName;
    private ResourceNamespace myNamespace;

    Loader(@NotNull Path resApkFile, @NotNull Predicate<Configuration> configFilter, @NotNull Predicate<ResourceType> resourceTypeFilter) {
      myResApkFile = resApkFile;
      myConfigFilter = configFilter;
      myResourceTypeFilter = resourceTypeFilter;

    }

    void readApkFile() throws IOException {
      try (ZipFile zipFile = new ZipFile(myResApkFile.toFile())) {
        myResourceTableMsg = readResourceTableFromResApk(zipFile);
        myPackageName = AndroidManifestPackageNameUtils.getPackageNameFromResApk(zipFile);
      } finally {
        myNamespace = myPackageName == null ? ResourceNamespace.RES_AUTO : ResourceNamespace.fromPackageName(myPackageName);
      }
    }

    public void loadRepositoryContents(@NotNull AarProtoResourceRepository repository) {
      if (myResourceTableMsg != null) {
        loadFromResourceTable(repository, myResourceTableMsg);
      }
    }

    private void loadFromResourceTable(@NotNull AarProtoResourceRepository repository, @NotNull Resources.ResourceTable resourceTableMsg) {
      // String pool is only needed if there is a source attachment.
      StringPool stringPool = repository.mySourceAttachmentPrefix == null ?
                              null : new StringPool(resourceTableMsg.getSourcePool(), myNamespace.getPackageName());

      for (Resources.Package packageMsg : resourceTableMsg.getPackageList()) {
        for (Resources.Type typeMsg : packageMsg.getTypeList()) {
          String typeName = typeMsg.getName();
          ResourceType resourceType = ResourceType.fromClassName(typeName);
          if (resourceType == null) {
            // AAPT2 emits "^attr-private" type for all non-public "attr" resources. For reference see http://b/122572805 and
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/tools/aapt2/link/Linkers.h#65.
            if (typeName.equals("^attr-private")) {
              resourceType = ResourceType.ATTR;
            }
            else {
              LOG.warn("Unexpected resource type: " + typeName);
              continue;
            }
          }
          if (myResourceTypeFilter.test(resourceType)) {
            for (Resources.Entry entryMsg : typeMsg.getEntryList()) {
              String resourceName = entryMsg.getName();
              Resources.Visibility visibilityMsg = entryMsg.getVisibility();
              ResourceVisibility visibility = decodeVisibility(visibilityMsg);
              for (Resources.ConfigValue configValueMsg : entryMsg.getConfigValueList()) {
                Resources.Value valueMsg = configValueMsg.getValue();
                Resources.Source sourceMsg = valueMsg.getSource();
                String sourcePath = stringPool == null ? null : stringPool.getString(sourceMsg.getPathIdx());
                if (sourcePath != null && sourcePath.isEmpty()) {
                  sourcePath = null;
                }
                Configuration configMsg = configValueMsg.getConfig();
                if (myConfigFilter.test(configMsg)) {
                  ResourceSourceFile sourceFile = getSourceFile(repository, sourcePath, configMsg);
                  ResourceItem item = createResourceItem(valueMsg, resourceType, resourceName, sourceFile, visibility);
                  if (item != null) {
                    addResourceItem(repository, item);
                  }
                }
              }
            }
          }
        }
      }

      for (BasicStyleableResourceItem styleable : myStyleables.values()) {
        repository.addResourceItem(RepositoryLoader.resolveAttrReferences(styleable));
      }

      repository.populatePublicResourcesMap();
      repository.freezeResources();
    }

    private void addResourceItem(@NotNull AarProtoResourceRepository repository, @NotNull ResourceItem item) {
      if (item.getType() == ResourceType.STYLEABLE) {
        myStyleables.put(item.getName(), (BasicStyleableResourceItem)item);
      }
      else {
        repository.addResourceItem(item);
      }
    }

    @Nullable
    private BasicResourceItem createResourceItem(@NotNull Resources.Value valueMsg, @NotNull ResourceType resourceType,
                                                 @NotNull String resourceName, @NotNull ResourceSourceFile sourceFile,
                                                 @NotNull ResourceVisibility visibility) {
      switch (valueMsg.getValueCase()) {
        case ITEM:
          return createResourceItem(valueMsg.getItem(), resourceType, resourceName, sourceFile, visibility);

        case COMPOUND_VALUE:
          String description = valueMsg.getComment();
          if (CharMatcher.whitespace().matchesAllOf(description)) {
            description = null;
          }
          return createResourceItem(valueMsg.getCompoundValue(), resourceName, sourceFile, visibility, description);

        case VALUE_NOT_SET:
        default:
          LOG.warn("Unexpected Value message: " + valueMsg);
          break;
      }
      return null;
    }

    @Nullable
    private BasicResourceItem createResourceItem(@NotNull Resources.Item itemMsg, @NotNull ResourceType resourceType,
                                                 @NotNull String resourceName, @NotNull ResourceSourceFile sourceFile,
                                                 @NotNull ResourceVisibility visibility) {
      switch (itemMsg.getValueCase()) {
        case FILE: {
          // For XML files, which contain proto XML that is not human-readable, use the source attachment path when available.
          // For other resources use the path inside res.apk.
          String path = sourceFile.getRelativePath();
          if (path == null || !isXml(path)) {
            path = itemMsg.getFile().getPath();
          }
          RepositoryConfiguration configuration = sourceFile.getConfiguration();
          if (DensityBasedResourceValue.isDensityBasedResourceType(resourceType)) {
            FolderConfiguration folderConfiguration = configuration.getFolderConfiguration();
            DensityQualifier densityQualifier = folderConfiguration.getDensityQualifier();
            if (densityQualifier != null) {
              Density densityValue = densityQualifier.getValue();
              if (densityValue != null) {
                return new BasicDensityBasedFileResourceItem(resourceType, resourceName, configuration, visibility, path, densityValue);
              }
            }
          }
          return new BasicFileResourceItem(resourceType, resourceName, configuration, visibility, path);
        }

        case REF: {
          String ref = decode(itemMsg.getRef());
          return createResourceItem(resourceType, resourceName, sourceFile, visibility, ref);
        }

        case STR: {
          String textValue = itemMsg.getStr().getValue();
          return new BasicValueResourceItem(resourceType, resourceName, sourceFile, visibility, textValue);
        }

        case RAW_STR: {
          String str = itemMsg.getRawStr().getValue();
          return createResourceItem(resourceType, resourceName, sourceFile, visibility, str);
        }

        case PRIM: {
          String str = decode(itemMsg.getPrim());
          return createResourceItem(resourceType, resourceName, sourceFile, visibility, str);
        }

        case STYLED_STR: {
          Resources.StyledString styledStrMsg = itemMsg.getStyledStr();
          String textValue = styledStrMsg.getValue();
          String rawXmlValue = ProtoStyledStringDecoder.getRawXmlValue(styledStrMsg);
          if (rawXmlValue.equals(textValue)) {
            return new BasicValueResourceItem(resourceType, resourceName, sourceFile, visibility, textValue);
          }
          return new BasicTextValueResourceItem(resourceType, resourceName, sourceFile, visibility, textValue, rawXmlValue);
        }

        case ID: {
          return createResourceItem(resourceType, resourceName, sourceFile, visibility, null);
        }

        case VALUE_NOT_SET:
        default:
          LOG.warn("Unexpected Item message: " + itemMsg);
          break;
      }
      return null;
    }

    @NotNull
    private static BasicResourceItem createResourceItem(@NotNull ResourceType resourceType, @NotNull String resourceName,
                                                        @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility,
                                                        @Nullable String value) {
      return new BasicValueResourceItem(resourceType, resourceName, sourceFile, visibility, value);
    }

    @Nullable
    private BasicResourceItem createResourceItem(@NotNull Resources.CompoundValue compoundValueMsg, @NotNull String resourceName,
                                                 @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility,
                                                 @Nullable String description) {
      switch (compoundValueMsg.getValueCase()) {
        case ATTR:
          return createAttr(compoundValueMsg.getAttr(), resourceName, sourceFile, visibility, description);

        case STYLE:
          return createStyle(compoundValueMsg.getStyle(), resourceName, sourceFile, visibility);

        case STYLEABLE:
          return createStyleable(compoundValueMsg.getStyleable(), resourceName, sourceFile, visibility);

        case ARRAY:
          return createArray(compoundValueMsg.getArray(), resourceName, sourceFile, visibility);

        case PLURAL:
          return createPlurals(compoundValueMsg.getPlural(), resourceName, sourceFile, visibility);

        case VALUE_NOT_SET:
        default:
          LOG.warn("Unexpected CompoundValue message: " + compoundValueMsg);
          return null;
      }
    }

    @NotNull
    private static BasicAttrResourceItem createAttr(@NotNull Resources.Attribute attributeMsg, @NotNull String resourceName,
                                                    @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility,
                                                    @Nullable String description) {
      Set<AttributeFormat> formats = decodeFormatFlags(attributeMsg.getFormatFlags());

      List<Resources.Attribute.Symbol> symbolList = attributeMsg.getSymbolList();
      Map<String, Integer> valueMap = Collections.emptyMap();
      Map<String, String> valueDescriptionMap = Collections.emptyMap();
      for (Resources.Attribute.Symbol symbolMsg : symbolList) {
        String name = symbolMsg.getName().getName();
        // Remove the explicit resource type to match the behavior of AarSourceResourceRepository.
        int slashPos = name.lastIndexOf('/');
        if (slashPos >= 0) {
          name = name.substring(slashPos + 1);
        }
        String symbolDescription = symbolMsg.getComment();
        if (CharMatcher.whitespace().matchesAllOf(symbolDescription)) {
          symbolDescription = null;
        }
        if (valueMap.isEmpty()) {
          valueMap = new HashMap<>();
        }
        valueMap.put(name, symbolMsg.getValue());
        if (symbolDescription != null) {
          if (valueDescriptionMap.isEmpty()) {
            valueDescriptionMap = new HashMap<>();
          }
          valueDescriptionMap.put(name, symbolDescription);
        }
      }

      String groupName = null; // Attribute group name is not available in a proto resource repository.
      return new BasicAttrResourceItem(resourceName, sourceFile, visibility, description, groupName, formats, valueMap, valueDescriptionMap);
    }

    @NotNull
    private BasicStyleResourceItem createStyle(@NotNull Resources.Style styleMsg, @NotNull String resourceName,
                                               @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility) {
      String libraryName = sourceFile.getRepository().getLibraryName();
      myUrlParser.parseResourceUrl(styleMsg.getParent().getName());
      String parentStyle = myUrlParser.getQualifiedName();
      if (StyleResourceValue.isDefaultParentStyleName(parentStyle, resourceName)) {
        parentStyle = null; // Don't store a parent style name that can be derived from the name of the style.
      }
      List<StyleItemResourceValue> styleItems = new ArrayList<>(styleMsg.getEntryCount());
      for (Resources.Style.Entry entryMsg : styleMsg.getEntryList()) {
        String url = entryMsg.getKey().getName();
        myUrlParser.parseResourceUrl(url);
        String name = myUrlParser.getQualifiedName();
        String value = decode(entryMsg.getItem());
        StyleItemResourceValueImpl itemValue = new StyleItemResourceValueImpl(myNamespace, name, value, libraryName);
        styleItems.add(itemValue);
      }

      return new BasicStyleResourceItem(resourceName, sourceFile, visibility, parentStyle, styleItems);
    }

    @NotNull
    private BasicStyleableResourceItem createStyleable(@NotNull Resources.Styleable styleableMsg, @NotNull String resourceName,
                                                       @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility) {
      List<AttrResourceValue> attrs = new ArrayList<>(styleableMsg.getEntryCount());
      for (Resources.Styleable.Entry entryMsg : styleableMsg.getEntryList()) {
        String url = entryMsg.getAttr().getName();
        myUrlParser.parseResourceUrl(url);
        String packageName = myUrlParser.getNamespacePrefix();
        ResourceNamespace attrNamespace = packageName == null ? myNamespace : ResourceNamespace.fromPackageName(packageName);
        String comment = entryMsg.getComment();
        BasicAttrReference attr =
            new BasicAttrReference(attrNamespace, myUrlParser.getName(), sourceFile, visibility, comment.isEmpty() ? null : comment, null);
        attrs.add(attr);
      }
      return new BasicStyleableResourceItem(resourceName, sourceFile, visibility, attrs);
    }

    @NotNull
    private BasicArrayResourceItem createArray(@NotNull Resources.Array arrayMsg, @NotNull String resourceName,
                                               @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility) {
      List<String> elements = new ArrayList<>(arrayMsg.getElementCount());
      for (Resources.Array.Element elementMsg : arrayMsg.getElementList()) {
        String text = decode(elementMsg.getItem());
        if (text != null) {
          elements.add(text);
        }
      }
      return new BasicArrayResourceItem(resourceName, sourceFile, visibility, elements, 0);
    }

    @NotNull
    private BasicPluralsResourceItem createPlurals(@NotNull Resources.Plural pluralMsg, @NotNull String resourceName,
                                                   @NotNull ResourceSourceFile sourceFile, @NotNull ResourceVisibility visibility) {
      EnumMap<Arity, String> values = new EnumMap<>(Arity.class);
      for (Resources.Plural.Entry entryMsg : pluralMsg.getEntryList()) {
        values.put(decodeArity(entryMsg.getArity()), decode(entryMsg.getItem()));
      }
      return new BasicPluralsResourceItem(resourceName, sourceFile, visibility, values, null);
    }

    @NotNull
    private ResourceSourceFile getSourceFile(@NotNull AarProtoResourceRepository repository, @Nullable String sourcePath,
                                             @NotNull Configuration configMsg) {
      String sourcePathKey = sourcePath == null ? "" : sourcePath;
      ResourceSourceFile sourceFile = mySourceFileCache.get(sourcePathKey, configMsg);
      if (sourceFile != null) {
        return sourceFile;
      }

      FolderConfiguration configuration = ProtoConfigurationDecoder.getConfiguration(configMsg);
      configuration.normalizeByRemovingRedundantVersionQualifier();

      sourceFile = new ResourceSourceFileImpl(sourcePath, new RepositoryConfiguration(repository, configuration));
      mySourceFileCache.put(sourcePathKey, configMsg, sourceFile);
      return sourceFile;
    }

    @Nullable
    private String decode(@NotNull Resources.Item itemMsg) {
      switch (itemMsg.getValueCase()) {
        case REF:
          return decode(itemMsg.getRef());
        case STR:
          return itemMsg.getStr().getValue();
        case RAW_STR:
          return itemMsg.getRawStr().getValue();
        case STYLED_STR:
          return itemMsg.getStyledStr().getValue();
        case FILE:
          return itemMsg.getFile().getPath();
        case ID:
          return null;
        case PRIM:
          return decode(itemMsg.getPrim());
        case VALUE_NOT_SET:
        default:
          break;
      }
      return null;
    }

    @NotNull
    private String decode(@NotNull Resources.Reference referenceMsg) {
      String name = referenceMsg.getName();
      if (name.isEmpty()) {
        return "";
      }
      if (referenceMsg.getType() == Resources.Reference.Type.ATTRIBUTE) {
        myUrlParser.parseResourceUrl(name);
        if (myUrlParser.hasType(ResourceType.ATTR.getName())) {
          name = myUrlParser.getQualifiedName();
        }
        return '?' + name;
      }
      return '@' + name;
    }

    @Nullable
    private static String decode(@NotNull Resources.Primitive primitiveMsg) {
      switch (primitiveMsg.getOneofValueCase()) {
        case NULL_VALUE:
          return null;

        case EMPTY_VALUE:
          return "";

        case FLOAT_VALUE:
          return trimInsignificantZeros(Float.toString(primitiveMsg.getFloatValue()));

        case DIMENSION_VALUE:
          return decodeComplexDimensionValue(primitiveMsg.getDimensionValue(), 1., DIMEN_SUFFIXES);

        case FRACTION_VALUE:
          return decodeComplexDimensionValue(primitiveMsg.getFractionValue(), 100., FRACTION_SUFFIXES);

        case INT_DECIMAL_VALUE:
          return Integer.toString(primitiveMsg.getIntDecimalValue());

        case INT_HEXADECIMAL_VALUE:
          return String.format("0x%X", primitiveMsg.getIntHexadecimalValue());

        case BOOLEAN_VALUE:
          return Boolean.toString(primitiveMsg.getBooleanValue());

        case COLOR_ARGB8_VALUE:
          return String.format("#%08X", primitiveMsg.getColorArgb8Value());

        case COLOR_RGB8_VALUE:
          return String.format("#%06X", primitiveMsg.getColorRgb8Value() & 0xFFFFFF);

        case COLOR_ARGB4_VALUE:
          int argb = primitiveMsg.getColorArgb4Value();
          return String.format("#%X%X%X%X", (argb >>> 24) & 0xF, (argb >>> 16) & 0xF, (argb >>> 8) & 0xF, argb & 0xF);

        case COLOR_RGB4_VALUE:
          int rgb = primitiveMsg.getColorRgb4Value();
          return String.format("#%X%X%X", (rgb >>> 16) & 0xF, (rgb >>> 8) & 0xF, rgb & 0xF);

        case ONEOFVALUE_NOT_SET:
        default:
          LOG.warn("Unexpected Primitive message: " + primitiveMsg);
          break;
      }
      return null;
    }

    /**
     * Decodes a dimension value in the Android binary XML encoding and returns a string suitable for regular XML.
     *
     * @param bits the encoded value
     * @param scaleFactor the scale factor to apply to the result
     * @param unitSuffixes the unit suffixes, either {@link #DIMEN_SUFFIXES} or {@link #FRACTION_SUFFIXES}
     * @return the decoded value as a string, e.g. "-6.5dp", or "60%"
     * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h">
     *     ResourceTypes.h</a>
     */
    private static String decodeComplexDimensionValue(int bits, double scaleFactor, @NotNull String[] unitSuffixes) {
      int unitCode = bits & COMPLEX_UNIT_MASK;
      String unit = unitCode < unitSuffixes.length ? unitSuffixes[unitCode] : " unknown unit: " + unitCode;
      int radix = (bits >> COMPLEX_RADIX_SHIFT) & COMPLEX_RADIX_MASK;
      int mantissa = bits >> COMPLEX_MANTISSA_SHIFT;
      double value = mantissa * RADIX_FACTORS[radix] * scaleFactor;
      return trimInsignificantZeros(String.format(Locale.US, "%.5g", value)) + unit;
    }

    @NotNull
    private static Set<AttributeFormat> decodeFormatFlags(int flags) {
      Set<AttributeFormat> result = EnumSet.noneOf(AttributeFormat.class);
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.REFERENCE_VALUE)) {
        result.add(AttributeFormat.REFERENCE);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.STRING_VALUE)) {
        result.add(AttributeFormat.STRING);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.INTEGER_VALUE)) {
        result.add(AttributeFormat.INTEGER);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.BOOLEAN_VALUE)) {
        result.add(AttributeFormat.BOOLEAN);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.COLOR_VALUE)) {
        result.add(AttributeFormat.COLOR);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FLOAT_VALUE)) {
        result.add(AttributeFormat.FLOAT);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.DIMENSION_VALUE)) {
        result.add(AttributeFormat.DIMENSION);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FRACTION_VALUE)) {
        result.add(AttributeFormat.FRACTION);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.ENUM_VALUE)) {
        result.add(AttributeFormat.ENUM);
      }
      if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FLAGS_VALUE)) {
        result.add(AttributeFormat.FLAGS);
      }
      return result;
    }

    @NotNull
    private static Arity decodeArity(@NotNull Resources.Plural.Arity arity) {
      switch (arity) {
        case ZERO:
          return Arity.ZERO;
        case ONE:
          return Arity.ONE;
        case TWO:
          return Arity.TWO;
        case FEW:
          return Arity.FEW;
        case MANY:
          return Arity.MANY;
        case OTHER:
        default:
          return Arity.OTHER;
      }
    }

    @NotNull
    private static ResourceVisibility decodeVisibility(@NotNull Resources.Visibility visibilityMsg) {
      switch (visibilityMsg.getLevel()) {
        case UNKNOWN:
          return ResourceVisibility.PRIVATE_XML_ONLY;
        case PRIVATE:
          return ResourceVisibility.PRIVATE;
        case PUBLIC:
          return ResourceVisibility.PUBLIC;
        case UNRECOGNIZED:
        default:
          return ResourceVisibility.UNDEFINED;
      }
    }

    /**
     * Loads resource table from res.apk file.
     *
     * @return the resource table proto message
     */
    @NotNull
    private static Resources.ResourceTable readResourceTableFromResApk(@NotNull ZipFile resApk) throws IOException {
      ZipEntry zipEntry = resApk.getEntry(RESOURCE_TABLE_ENTRY);
      if (zipEntry == null) {
        throw new IOException("\"" + RESOURCE_TABLE_ENTRY + "\" not found in " + resApk.getName());
      }

      try (InputStream stream = new BufferedInputStream(resApk.getInputStream(zipEntry))) {
        return Resources.ResourceTable.parseFrom(stream);
      }
    }
  }

  /**
   * Extracts strings encoded inside a {@link Resources.StringPool} proto message.
   */
  private static class StringPool {
    // See definition of the ResStringPool_header structure at
    // https://android.googlesource.com/platform/frameworks/base/+/tools_r22.2/include/androidfw/ResourceTypes.h
    private static final int STRING_COUNT_OFFSET = 8;
    private static final int FLAGS_OFFSET = 16;
    private static final int STRINGS_START_INDEX_OFFSET = 20;
    private static final int UTF8_FLAG = 1 << 8;
    private static final String REPLACEMENT_PREFIX = "0/res/";

    @NotNull final String[] strings;
    private int currentOffset;

    StringPool(@NotNull Resources.StringPool stringPoolMsg, @Nullable String packageName) {
      ByteString bytes = stringPoolMsg.getData();
      if ((getInt32(bytes, FLAGS_OFFSET) & UTF8_FLAG) == 0) {
        throw new IllegalArgumentException("UTF-16 encoded string pool is not supported");
      }
      int stringCount = getInt32(bytes, STRING_COUNT_OFFSET);
      strings = new String[stringCount];
      currentOffset = getInt32(bytes, STRINGS_START_INDEX_OFFSET);
      for (int i = 0; i < stringCount; i++) {
        getByteEncodedLength(bytes); // Skip the number of characters.
        int byteCount = getByteEncodedLength(bytes);
        int endOffset = currentOffset + byteCount;
        strings[i] = bytes.substring(currentOffset, endOffset).toStringUtf8();
        currentOffset = endOffset + 1; // Skip the bytes of the string including the 0x00 terminator.
      }
      normalizePaths(packageName);
    }

    private static int getByte(@NotNull ByteString bytes, int offset) {
      return bytes.byteAt(offset) & 0xFF;
    }

    private static int getInt32(@NotNull ByteString bytes, int offset) {
      return getByte(bytes, offset) |
             (getByte(bytes, offset + 1) << 8) |
             (getByte(bytes, offset + 2) << 16) |
             (getByte(bytes, offset + 3) << 24);
    }

    /**
     * Decodes a length value encoded using the EncodeLength(char*, size_t) function defined at
     * https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/StringPool.cpp
     */
    private int getByteEncodedLength(@NotNull ByteString bytes) {
      int b = getByte(bytes, currentOffset++);
      if ((b & 0x80) == 0) {
        return b;
      }
      return (b & 0x7F) << 8 | getByte(bytes, currentOffset++);
    }

    /**
     * Source paths in AARv2 are supposed to be relative, but currently AAPT2 inserts absolute paths.
     * This method works around this AAPT2 limitation by converting source paths to the form they are
     * supposed to have.
     */
    private void normalizePaths(@Nullable String packageName) {
      String packagePrefix = packageName == null ? null : getPackageNamePrefix(packageName);
      String prefix = null;
      for (int i = 0, n = strings.length; i < n; i++) {
        String str = strings[i];
        if (!str.isEmpty()) {
          str = portableFileName(str);
          if (str.charAt(0) == '/') {
            // The string represents an absolute path. Convert it to a relative path.
            if (prefix == null) {
              String anchor = "/res/";
              int pos = str.indexOf(anchor);
              if (pos >= 0) {
                prefix = str.substring(0, pos + anchor.length());
              }
            }
            if (prefix == null) {
              String anchor = "/namespaced_res/";
              int pos = str.indexOf(anchor);
              if (pos >= 0) {
                // Skip the following directory segment that reflects the name of the library.
                pos = str.indexOf('/', pos + anchor.length());
                if (pos >= 0) {
                  prefix = str.substring(0, pos + 1);
                }
              }
            }
            if (prefix != null && str.startsWith(prefix)) {
              str = REPLACEMENT_PREFIX + str.substring(prefix.length());
            }
          }
          else if (packagePrefix != null && str.startsWith(packagePrefix)) {
            // The string represents a relative path. Remove the package prefix if present.
            str = str.substring(packagePrefix.length());
          }

          strings[i] = str;
        }
      }
    }

    @NotNull
    public String getString(int index) {
      return strings[index];
    }
  }
}