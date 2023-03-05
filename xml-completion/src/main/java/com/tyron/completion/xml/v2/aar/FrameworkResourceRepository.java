package com.tyron.completion.xml.v2.aar;

import static com.android.SdkConstants.DOT_9PNG;
import static com.android.SdkConstants.FD_RES_RAW;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.resources.configuration.LocaleQualifier;
import com.android.ide.common.util.PathString;
import com.android.io.CancellableFileIo;
import com.android.resources.ResourceType;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128OutputStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.xml.v2.base.BasicResourceItem;
import com.tyron.completion.xml.v2.base.BasicResourceItemBase;
import com.tyron.completion.xml.v2.base.BasicValueResourceItemBase;
import com.tyron.completion.xml.v2.base.NamespaceResolver;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.completion.xml.v2.base.RepositoryLoader;
import com.tyron.completion.xml.v2.base.ResourceSerializationUtil;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;

/**
 * Repository of resources of the Android framework. Most client code should use
 * the ResourceRepositoryManager.getFrameworkResources method to obtain framework resources.
 *
 * <p>The repository can be loaded either from a res directory containing XML files, or from
 * framework_res.jar file, or from a binary cache file located under the directory returned by
 * the {@link PathManager#getSystemPath()} method. This binary cache file can be created as
 * a side effect of loading the repository from a res directory.
 *
 * <p>Loading from framework_res.jar or a binary cache file is 3-4 times faster than loading
 * from res directory.
 *
 * @see FrameworkResJarCreator
 */
public final class FrameworkResourceRepository extends AarSourceResourceRepository {
  private static final ResourceNamespace ANDROID_NAMESPACE = ResourceNamespace.ANDROID;
  /** Mapping from languages to language groups, e.g. Romansh is mapped to Italian. */
  private static final Map<String, String> LANGUAGE_TO_GROUP = ImmutableMap.of("rm", "it");
  private static final String RESOURCES_TABLE_PREFIX = "resources_";
  private static final String RESOURCE_TABLE_SUFFIX = ".bin";
  private static final String COMPILED_9PNG_EXTENSION = ".compiled.9.png";

  private static final Logger LOG = Logger.getInstance(FrameworkResourceRepository.class);

  private final Set<String> myLanguageGroups = new TreeSet<>();
  private int myNumberOfLanguageGroupsLoadedFromCache;
  private final boolean myUseCompiled9Patches;

  private FrameworkResourceRepository(@NotNull RepositoryLoader<FrameworkResourceRepository> loader, boolean useCompiled9Patches) {
    super(loader, null);
    myUseCompiled9Patches = useCompiled9Patches;
  }

  /**
   * Creates an Android framework resource repository.
   *
   * @param resourceDirectoryOrFile the res directory or a jar file containing resources of the Android framework
   * @param languagesToLoad the set of ISO 639 language codes, or null to load all available languages
   * @param cachingData data used to validate and create a persistent cache file
   * @param useCompiled9Patches whether to provide the compiled or non-compiled version of the framework 9-patches
   * @return the created resource repository
   */
  @NotNull
  public static FrameworkResourceRepository create(@NotNull Path resourceDirectoryOrFile, @Nullable Set<String> languagesToLoad,
                                                   @Nullable CachingData cachingData, boolean useCompiled9Patches) {
    long start = LOG.isDebugEnabled() ? System.currentTimeMillis() : 0;
    Set<String> languageGroups = languagesToLoad == null ? null : getLanguageGroups(languagesToLoad);

    Loader loader = new Loader(resourceDirectoryOrFile, languageGroups);
    FrameworkResourceRepository repository = new FrameworkResourceRepository(loader, useCompiled9Patches);

    repository.load(null, cachingData, loader, languageGroups, loader.myLoadedLanguageGroups);

    if (LOG.isDebugEnabled()) {
      String source = repository.getNumberOfLanguageGroupsLoadedFromOrigin() == 0 ?
                      "cache" :
                      repository.myNumberOfLanguageGroupsLoadedFromCache == 0 ?
                      resourceDirectoryOrFile.toString() :
                      "cache and " + resourceDirectoryOrFile;
      LOG.debug("Loaded from " + source + " with " + (repository.myLanguageGroups.size() - 1) + " languages in " +
                (System.currentTimeMillis() - start) / 1000. + " sec");
    }
    return repository;
  }

  /**
   * Checks if the repository contains resources for the given set of languages.
   *
   * @param languages the set of ISO 639 language codes to check
   * @return true if the repository contains resources for all requested languages
   */
  public boolean containsLanguages(@NotNull Set<String> languages) {
    for (String language : languages) {
      if (!myLanguageGroups.contains(getLanguageGroup(language))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Loads resources for requested languages that are not present in this resource repository.
   *
   * @param languagesToLoad the set of ISO 639 language codes, or null to load all available languages
   * @param cachingData data used to validate and create a persistent cache file
   * @return the new resource repository with additional resources, or this resource repository if it already contained
   *     all requested languages
   */
  @NotNull
  public FrameworkResourceRepository loadMissingLanguages(@Nullable Set<String> languagesToLoad, @Nullable CachingData cachingData) {
    @Nullable Set<String> languageGroups = languagesToLoad == null ? null : getLanguageGroups(languagesToLoad);
    if (languageGroups != null && myLanguageGroups.containsAll(languageGroups)) {
      return this; // The repository already contains all requested languages.
    }

    long start = LOG.isDebugEnabled() ? System.currentTimeMillis() : 0;
    Loader loader = new Loader(this, languageGroups);
    FrameworkResourceRepository newRepository = new FrameworkResourceRepository(loader, myUseCompiled9Patches);

    newRepository.load(this, cachingData, loader, languageGroups, loader.myLoadedLanguageGroups);

    if (LOG.isDebugEnabled()) {
      String source = newRepository.getNumberOfLanguageGroupsLoadedFromOrigin() == getNumberOfLanguageGroupsLoadedFromOrigin() ?
                      "cache" :
                      newRepository.myNumberOfLanguageGroupsLoadedFromCache == myNumberOfLanguageGroupsLoadedFromCache ?
                      myResourceDirectoryOrFile.toString() :
                      "cache and " + myResourceDirectoryOrFile;
      LOG.debug("Loaded " + (newRepository.myLanguageGroups.size() - myLanguageGroups.size()) + " additional languages from " + source +
                " in " + (System.currentTimeMillis() - start) / 1000. + " sec");
    }
    return newRepository;
  }

  private void load(@Nullable FrameworkResourceRepository sourceRepository,
                    @Nullable CachingData cachingData,
                    @NotNull Loader loader,
                    @Nullable Set<String> languageGroups,
                    @NotNull Set<String> languageGroupsLoadedFromSourceRepositoryOrCache) {
    Map<String, String> stringCache = Maps.newHashMapWithExpectedSize(10000);
    Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache = new HashMap<>();
    Set<RepositoryConfiguration> configurationsToTakeOver =
        sourceRepository == null ? ImmutableSet.of() : copyFromRepository(sourceRepository, stringCache, namespaceResolverCache);

    // If not loading from a jar file, try to load from a cache file first. A separate cache file is not used
    // when loading from framework_res.jar since it already contains data in the cache format. Loading from
    // framework_res.jar or a cache file is significantly faster than reading individual resource files.
    if (!loader.isLoadingFromZipArchive() && cachingData != null) {
      loadFromPersistentCache(cachingData, languageGroups, languageGroupsLoadedFromSourceRepositoryOrCache, stringCache,
                              namespaceResolverCache);
    }

    myLanguageGroups.addAll(languageGroupsLoadedFromSourceRepositoryOrCache);
    if (languageGroups == null || !languageGroupsLoadedFromSourceRepositoryOrCache.containsAll(languageGroups)) {
      loader.loadRepositoryContents(this);
    }

    myLoadedFromCache = myNumberOfLanguageGroupsLoadedFromCache == myLanguageGroups.size();

    populatePublicResourcesMap();
    freezeResources();
    takeOverConfigurations(configurationsToTakeOver);

    if (!loader.isLoadingFromZipArchive() && cachingData != null) {
      Executor executor = cachingData.getCacheCreationExecutor();
      if (executor != null && !languageGroupsLoadedFromSourceRepositoryOrCache.containsAll(myLanguageGroups)) {
        executor.execute(() -> createPersistentCache(cachingData, languageGroupsLoadedFromSourceRepositoryOrCache));
      }
    }
  }

  @Override
  @Nullable
  public String getPackageName() {
    return ANDROID_NAMESPACE.getPackageName();
  }

  @Override
  @NotNull
  public Set<ResourceType> getResourceTypes(@NotNull ResourceNamespace namespace) {
    return namespace == ANDROID_NAMESPACE ? Sets.immutableEnumSet(myResources.keySet()) : ImmutableSet.of();
  }

  /**
   * Copies resources from another FrameworkResourceRepository.
   *
   * @param sourceRepository the repository to copy resources from
   * @param stringCache the string cache to populate with the names of copied resources
   * @param namespaceResolverCache the namespace resolver cache to populate with namespace resolvers referenced by the copied resources
   * @return the {@link RepositoryConfiguration} objects referenced by the copied resources
   */
  @NotNull
  private Set<RepositoryConfiguration> copyFromRepository(@NotNull FrameworkResourceRepository sourceRepository,
                                                          @NotNull Map<String, String> stringCache,
                                                          @NotNull Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache) {
    Collection<ListMultimap<String, ResourceItem>> resourceMaps = sourceRepository.myResources.values();

    // Copy resources from the source repository, get AarConfigurations that need to be taken over by this repository,
    // and pre-populate string and namespace resolver caches.
    Set<RepositoryConfiguration> sourceConfigurations = Sets.newIdentityHashSet();
    for (ListMultimap<String, ResourceItem> resourceMap : resourceMaps) {
      for (ResourceItem item : resourceMap.values()) {
        addResourceItem(item);

        sourceConfigurations.add(((BasicResourceItemBase)item).getRepositoryConfiguration());
        if (item instanceof BasicValueResourceItemBase) {
          ResourceNamespace.Resolver resolver = ((BasicValueResourceItemBase)item).getNamespaceResolver();
          NamespaceResolver namespaceResolver =
              resolver == ResourceNamespace.Resolver.EMPTY_RESOLVER ? NamespaceResolver.EMPTY : (NamespaceResolver)resolver;
          namespaceResolverCache.put(namespaceResolver, namespaceResolver);
        }
        String name = item.getName();
        stringCache.put(name, name);
      }
    }

    myNumberOfLanguageGroupsLoadedFromCache += sourceRepository.myNumberOfLanguageGroupsLoadedFromCache;
    return sourceConfigurations;
  }

  private void loadFromPersistentCache(@NotNull CachingData cachingData, @Nullable Set<String> languagesToLoad,
                                       @NotNull Set<String> loadedLanguages,
                                       @NotNull Map<String, String> stringCache,
                                       @Nullable Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache) {
    CacheFileNameGenerator fileNameGenerator = new CacheFileNameGenerator((cachingData));
    Set<String> languages = languagesToLoad == null ? fileNameGenerator.getAllCacheFileLanguages() : languagesToLoad;

    for (String language : languages) {
      if (!loadedLanguages.contains(language)) {
        Path cacheFile = fileNameGenerator.getCacheFile(language);
        try (Base128InputStream stream = new Base128InputStream(cacheFile)) {
          byte[] header = ResourceSerializationUtil.getCacheFileHeader(s -> writeCacheHeaderContent(cachingData, language, s));
          if (!stream.validateContents(header)) {
            // Cache file header doesn't match.
            if (language.isEmpty()) {
              break; // Don't try to load language-specific resources if language-neutral ones could not be loaded.
            }
            continue;
          }
          loadFromStream(stream, stringCache, namespaceResolverCache);
          loadedLanguages.add(language);
          myNumberOfLanguageGroupsLoadedFromCache++;
        }
        catch (NoSuchFileException e) {
          // Cache file does not exist.
          if (language.isEmpty()) {
            break;  // Don't try to load language-specific resources if language-neutral ones could not be loaded.
          }
        }
        catch (ProcessCanceledException e) {
          cleanupAfterFailedLoadingFromCache();
          loadedLanguages.clear();
          throw e;
        }
        catch (Throwable e) {
          cleanupAfterFailedLoadingFromCache();
          loadedLanguages.clear();
          LOG.warn("Failed to load from cache file " + cacheFile.toString(), e);
          break;
        }
      }
    }
  }

  @Override
  protected void cleanupAfterFailedLoadingFromCache() {
    super.cleanupAfterFailedLoadingFromCache();
    myNumberOfLanguageGroupsLoadedFromCache = 0;
  }

  private void createPersistentCache(@NotNull CachingData cachingData, @NotNull Set<String> languagesToSkip) {
    CacheFileNameGenerator fileNameGenerator = new CacheFileNameGenerator(cachingData);
    for (String language : myLanguageGroups) {
      if (!languagesToSkip.contains(language)) {
        Path cacheFile = fileNameGenerator.getCacheFile(language);
        byte[] header = ResourceSerializationUtil.getCacheFileHeader(stream -> writeCacheHeaderContent(cachingData, language, stream));
        ResourceSerializationUtil.createPersistentCache(
            cacheFile, header, stream -> writeToStream(stream, config -> language.equals(getLanguageGroup(config))));
      }
    }
  }

  private void writeCacheHeaderContent(@NotNull CachingData cachingData, @NotNull String language, @NotNull Base128OutputStream stream)
      throws IOException {
    writeCacheHeaderContent(cachingData, stream);
    stream.writeString(language);
  }

  /**
   * Returns the name of the resource table file containing resources for the given language.
   *
   * @param language the two-letter language abbreviation, or an empty string for language-neutral resources
   * @return the file name
   */
  static String getResourceTableNameForLanguage(@NotNull String language) {
    return language.isEmpty() ? "resources.bin" : RESOURCES_TABLE_PREFIX + language + RESOURCE_TABLE_SUFFIX;
  }

  @NotNull
  static String getLanguageGroup(@NotNull FolderConfiguration config) {
    LocaleQualifier locale = config.getLocaleQualifier();
    return locale == null ? "" : getLanguageGroup(StringUtil.notNullize(locale.getLanguage()));
  }

  /**
   * Maps some languages to others effectively grouping languages together. For example, Romansh language
   * that has very few framework resources is grouped together with Italian.
   *
   * @param language the original language
   * @return the language representing the corresponding group of languages
   */
  @NotNull
  private static String getLanguageGroup(@NotNull String language) {
    return LANGUAGE_TO_GROUP.getOrDefault(language, language);
  }

  @NotNull
  private static Set<String> getLanguageGroups(@NotNull Set<String> languages) {
    Set<String> result = new TreeSet<>();
    result.add("");
    for (String language : languages) {
      result.add(getLanguageGroup(language));
    }
    return result;
  }

  @NotNull
  Set<String> getLanguageGroups() {
    Set<String> languages = new TreeSet<>();

    for (ListMultimap<String, ResourceItem> resourceMap : myResources.values()) {
      for (ResourceItem item : resourceMap.values()) {
        FolderConfiguration config = item.getConfiguration();
        languages.add(getLanguageGroup(config));
      }
    }

    return languages;
  }

  private int getNumberOfLanguageGroupsLoadedFromOrigin() {
    return myLanguageGroups.size() - myNumberOfLanguageGroupsLoadedFromCache;
  }

  @TestOnly
  int getNumberOfLanguageGroupsLoadedFromCache() {
    return myNumberOfLanguageGroupsLoadedFromCache;
  }

  @NotNull
  private String updateResourcePath(@NotNull String relativeResourcePath) {
    if (myUseCompiled9Patches && relativeResourcePath.endsWith(DOT_9PNG)) {
      return StringUtil.replaceSubSequence(relativeResourcePath,
                                         relativeResourcePath.length() - DOT_9PNG.length(),
                                          relativeResourcePath.length(),
                                         COMPILED_9PNG_EXTENSION).toString();
    }
    return relativeResourcePath;
  }

  @Override
  @NotNull
  public String getResourceUrl(@NotNull String relativeResourcePath) {
    return super.getResourceUrl(updateResourcePath(relativeResourcePath));
  }

  @Override
  @NotNull
  public PathString getSourceFile(@NotNull String relativeResourcePath, boolean forFileResource) {
    return super.getSourceFile(updateResourcePath(relativeResourcePath), forFileResource);
  }

  private static class Loader extends RepositoryLoader<FrameworkResourceRepository> {
    @NotNull private final List<String> myPublicFileNames = ImmutableList.of("public.xml", "public-final.xml", "public-staging.xml");
    @NotNull private final Set<String> myLoadedLanguageGroups;
    @Nullable private Set<String> myLanguageGroups;

    Loader(@NotNull Path resourceDirectoryOrFile, @Nullable Set<String> languageGroups) {
      super(resourceDirectoryOrFile, null, ANDROID_NAMESPACE);
      myLanguageGroups = languageGroups;
      myLoadedLanguageGroups = new TreeSet<>();
    }

    Loader(@NotNull FrameworkResourceRepository sourceRepository, @Nullable Set<String> languageGroups) {
      super(sourceRepository.myResourceDirectoryOrFile, null, ANDROID_NAMESPACE);
      myLanguageGroups = languageGroups;
      myLoadedLanguageGroups = new TreeSet<>(sourceRepository.myLanguageGroups);
    }

    public List<String> getPublicXmlFileNames() {
      return myPublicFileNames;
    }

    @Override
    protected void loadFromZip(@NotNull FrameworkResourceRepository repository) {
      try (ZipFile zipFile = new ZipFile(myResourceDirectoryOrFile.toFile())) {
        if (myLanguageGroups == null) {
          myLanguageGroups = readLanguageGroups(zipFile);
        }

        Map<String, String> stringCache = Maps.newHashMapWithExpectedSize(10000);
        Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache = new HashMap<>();

        for (String language : myLanguageGroups) {
          if (!myLoadedLanguageGroups.contains(language)) {
            String entryName = getResourceTableNameForLanguage(language);
            ZipEntry zipEntry = zipFile.getEntry(entryName);
            if (zipEntry == null) {
              if (language.isEmpty()) {
                throw new IOException("\"" + entryName + "\" not found in " + myResourceDirectoryOrFile.toString());
              }
              else {
                continue; // Requested language may not be represented in the Android framework resources.
              }
            }

            try (Base128InputStream stream = new Base128InputStream(zipFile.getInputStream(zipEntry))) {
              repository.loadFromStream(stream, stringCache, namespaceResolverCache);
            }
          }
        }

        repository.populatePublicResourcesMap();
        repository.freezeResources();
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (Exception e) {
        LOG.error("Failed to load resources from " + myResourceDirectoryOrFile.toString(), e);
      }
    }

    @NotNull
    private static Set<String> readLanguageGroups(@NotNull ZipFile zipFile) {
      ImmutableSortedSet.Builder<String> result = ImmutableSortedSet.naturalOrder();
      result.add("");
      zipFile.stream().forEach(entry -> {
        String name = entry.getName();
        if (name.startsWith(RESOURCES_TABLE_PREFIX) && name.endsWith(RESOURCE_TABLE_SUFFIX) &&
            name.length() == RESOURCES_TABLE_PREFIX.length() + RESOURCE_TABLE_SUFFIX.length() + 2 &&
            Character.isLetter(name.charAt(RESOURCES_TABLE_PREFIX.length())) &&
            Character.isLetter(name.charAt(RESOURCES_TABLE_PREFIX.length() + 1))) {
          result.add(name.substring(RESOURCES_TABLE_PREFIX.length(), RESOURCES_TABLE_PREFIX.length() + 2));
        }
      });
      return result.build();
    }

    @Override
    public void loadRepositoryContents(@NotNull FrameworkResourceRepository repository) {
      super.loadRepositoryContents(repository);

      Set<String> languageGroups = myLanguageGroups == null ? repository.getLanguageGroups() : myLanguageGroups;
      repository.myLanguageGroups.addAll(languageGroups);
    }

    @Override
    public boolean isIgnored(@NotNull Path fileOrDirectory, @NotNull BasicFileAttributes attrs) {
      if (fileOrDirectory.equals(myResourceDirectoryOrFile)) {
        return false;
      }

      if (super.isIgnored(fileOrDirectory, attrs)) {
        return true;
      }

      String fileName = fileOrDirectory.getFileName().toString();
      if (attrs.isDirectory()) {
        if (fileName.startsWith("values-mcc") ||
            fileName.startsWith(FD_RES_RAW) && (fileName.length() == FD_RES_RAW.length() || fileName.charAt(FD_RES_RAW.length()) == '-')) {
          return true; // Mobile country codes and raw resources are not used by LayoutLib.
        }

        // Skip folders that don't belong to languages in myLanguageGroups or languages that were loaded earlier.
        if (myLanguageGroups != null || !myLoadedLanguageGroups.isEmpty()) {
          FolderConfiguration config = FolderConfiguration.getConfigForFolder(fileName);
          if (config == null) {
            return true;
          }
          String language = getLanguageGroup(config);
          if ((myLanguageGroups != null && !myLanguageGroups.contains(language)) || myLoadedLanguageGroups.contains(language)) {
            return true;
          }
          myFolderConfigCache.put(config.getQualifierString(), config);
        }
      }
      else if ((myPublicFileNames.contains(fileName) || fileName.equals("symbols.xml")) &&
               "values".equals(new PathString(fileOrDirectory).getParentFileName())) {
        return true; // Skip files that don't contain resources.
      }
      else if (fileName.endsWith(COMPILED_9PNG_EXTENSION)) {
        return true;
      }

      return false;
    }

    @Override
    protected final void addResourceItem(@NotNull BasicResourceItem item, @NotNull FrameworkResourceRepository repository) {
      repository.addResourceItem(item);
    }

    @Override
    @NotNull
    protected String getKeyForVisibilityLookup(@NotNull String resourceName) {
      // This class obtains names of public resources from public.xml where all resource names are preserved
      // in their original form. This is different from the superclass that obtains the names from public.txt
      // where the names are transformed by replacing dots, colons and dashes with underscores.
      return resourceName;
    }
  }

  /**
   * Redirects the {@link RepositoryConfiguration} inherited from another repository to point to this one, so that
   * the other repository can be garbage collected. This has to be done after this repository is fully loaded.
   *
   * @param sourceConfigurations the configurations to reparent
   */
  private void takeOverConfigurations(@NotNull Set<RepositoryConfiguration> sourceConfigurations) {
    for (RepositoryConfiguration configuration : sourceConfigurations) {
      configuration.transferOwnershipTo(this);
    }
  }

  private static class CacheFileNameGenerator {
    private final Path myLanguageNeutralFile;
    private final String myPrefix;
    private final String mySuffix;

    CacheFileNameGenerator(@NotNull CachingData cachingData) {
      myLanguageNeutralFile = cachingData.getCacheFile();
      String fileName = myLanguageNeutralFile.getFileName().toString();
      int dotPos = fileName.lastIndexOf('.');
      myPrefix = dotPos >= 0 ? fileName.substring(0, dotPos) : fileName;
      mySuffix = dotPos >= 0 ? fileName.substring(dotPos) : "";
    }

    @NotNull
    Path getCacheFile(@NotNull String language) {
      return language.isEmpty() ? myLanguageNeutralFile : myLanguageNeutralFile.resolveSibling(myPrefix + '_' + language + mySuffix);
    }

    /**
     * Determines language from a cache file name.
     *
     * @param cacheFileName the name of a cache file
     * @return the language of resources contained in the cache file, or null if {@code cacheFileName}
     *     doesn't match the pattern of cache file names.
     */
    @Nullable
    String getLanguage(@NotNull String cacheFileName) {
      if (!cacheFileName.startsWith(myPrefix) || !cacheFileName.endsWith(mySuffix)) {
        return null;
      }
      int baseLength = myPrefix.length() + mySuffix.length();
      if (cacheFileName.length() == baseLength) {
        return "";
      }
      if (cacheFileName.length() != baseLength + 3 || cacheFileName.charAt(myPrefix.length()) != '_') {
        return null;
      }
      String language = cacheFileName.substring(myPrefix.length() + 1, myPrefix.length() + 3);
      if (!isLowerCaseLatinLetter(language.charAt(0)) || !isLowerCaseLatinLetter(language.charAt(1))) {
        return null;
      }
      return language;
    }

    @NotNull
    public Set<String> getAllCacheFileLanguages() {
      Set<String> result = new TreeSet<>();
      try (Stream<Path> stream = CancellableFileIo.list(myLanguageNeutralFile.getParent())) {
        stream.forEach(file -> {
          String language = getLanguage(file.getFileName().toString());
          if (language != null) {
            result.add(language);
          }
        });
      }
      catch (IOException ignore) {
      }
      return result;
    }

    private static boolean isLowerCaseLatinLetter(char c) {
      return 'a' <= c && c <= 'z';
    }
  }
}