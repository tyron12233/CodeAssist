package com.tyron.completion.xml.v2.aar;

import static com.android.SdkConstants.FN_ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.FN_PUBLIC_TXT;
import static com.android.SdkConstants.FN_RESOURCE_TEXT;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.AndroidManifestPackageNameUtils;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.symbols.Symbol;
import com.android.ide.common.symbols.SymbolIo;
import com.android.ide.common.symbols.SymbolTable;
import com.android.ide.common.util.PathString;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128OutputStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.xml.v2.base.BasicFileResourceItem;
import com.tyron.completion.xml.v2.base.BasicResourceItem;
import com.tyron.completion.xml.v2.base.NamespaceResolver;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.completion.xml.v2.base.RepositoryLoader;
import com.tyron.completion.xml.v2.base.ResourceSerializationUtil;
import com.tyron.completion.xml.v2.base.ResourceSourceFile;
import com.tyron.completion.xml.v2.base.ResourceSourceFileImpl;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableLazyValue;

/**
 * A resource repository representing unpacked contents of a non-namespaced AAR.
 *
 * For performance reasons ID resources defined using @+id syntax in layout XML files are
 * obtained from R.txt instead, when it is available. This means that
 * {@link ResourceItem#getOriginalSource()} method may return null for such ID resources.
 */
public class AarSourceResourceRepository extends AbstractAarResourceRepository {
  /**
   * Increment when making changes that may affect content of repository cache files.
   * Used together with CachingData.codeVersion. Important for developer builds.
   */
  static final String CACHE_FILE_FORMAT_VERSION = "3";
  private static final byte[] CACHE_FILE_HEADER = "Resource cache".getBytes(UTF_8);
  private static final Logger LOG = IdeLog.getCurrentLogger(AarSourceResourceRepository.class);

  @NotNull protected final Path myResourceDirectoryOrFile;
  protected boolean myLoadedFromCache;
  /**
   * Protocol used for constructing {@link PathString}s returned by the {@link BasicFileResourceItem#getSource()} method.
   */
  @NotNull private final String mySourceFileProtocol;
  /**
   * Common prefix of paths of all file resources.  Used to compose resource paths returned by
   * the {@link BasicFileResourceItem#getSource()} method.
   */
  @NotNull private final String myResourcePathPrefix;
  /**
   * Common prefix of URLs of all file resources. Used to compose resource URLs returned by
   * the {@link BasicFileResourceItem#getValue()} method.
   */
  @NotNull private final String myResourceUrlPrefix;
  /** The package name read on-demand from the manifest. */
  @NotNull private final NullableLazyValue<String> myManifestPackageName;

  protected AarSourceResourceRepository(@NotNull RepositoryLoader<? extends AarSourceResourceRepository> loader,
                                        @Nullable String libraryName) {
    super(loader.getNamespace(), libraryName);
    myResourceDirectoryOrFile = loader.getResourceDirectoryOrFile();
    mySourceFileProtocol = loader.getSourceFileProtocol();
    myResourcePathPrefix = loader.getResourcePathPrefix();
    myResourceUrlPrefix = loader.getResourceUrlPrefix();

    myManifestPackageName = NullableLazyValue.createValue(() -> {
      try {
        PathString manifestPath = getSourceFile("../" + FN_ANDROID_MANIFEST_XML, true);
        return AndroidManifestPackageNameUtils.getPackageNameFromManifestFile(manifestPath);
      }
      catch (FileNotFoundException e) {
        return null;
      }
      catch (IOException e) {
        LOG.severe("Failed to read manifest " + FN_ANDROID_MANIFEST_XML + " for " + getDisplayName() + "\n" + e);
        return null;
      }
    });
  }

  /**
   * Creates and loads a resource repository. Consider calling AarResourceRepositoryCache.getSourceRepository instead of this
   * method.
   *
   * @param resourceDirectoryOrFile the res directory or an AAR file containing resources
   * @param libraryName the name of the library
   * @return the created resource repository
   */
  @NotNull
  public static AarSourceResourceRepository create(@NotNull Path resourceDirectoryOrFile, @NotNull String libraryName) {
    return create(resourceDirectoryOrFile, libraryName, null);
  }

  /**
   * Creates and loads a resource repository. Consider calling AarResourceRepositoryCache.getSourceRepository instead of this
   * method.
   *
   * @param resourceDirectoryOrFile the res directory or an AAR file containing resources
   * @param libraryName the name of the library
   * @param cachingData data used to validate and create a persistent cache file
   * @return the created resource repository
   */
  @NotNull
  public static AarSourceResourceRepository create(@NotNull Path resourceDirectoryOrFile, @NotNull String libraryName,
                                                   @Nullable CachingData cachingData) {
    return create(resourceDirectoryOrFile, null, ResourceNamespace.RES_AUTO, libraryName, cachingData);
  }

  /**
   * Creates and loads a resource repository without using a persistent cache. Consider calling
   * AarResourceRepositoryCache.getSourceRepository instead of this method.
   *
   * @param resourceFolderRoot specifies the resource files to be loaded. The list of files to be loaded can be restricted by providing
   *     a not null {@code resourceFolderResources} list of files and subdirectories that should be loaded.
   * @param resourceFolderResources A null value indicates that all files and subdirectories in {@code resourceFolderRoot} should be loaded.
   *     Otherwise files and subdirectories specified in {@code resourceFolderResources} are loaded.
   * @param libraryName the name of the library
   * @param cachingData data used to validate and create a persistent cache file
   * @return the created resource repository
   */
  @NotNull
  public static AarSourceResourceRepository create(@NotNull PathString resourceFolderRoot,
                                                   @Nullable Collection<PathString> resourceFolderResources,
                                                   @NotNull String libraryName,
                                                   @Nullable CachingData cachingData) {
    Path resDir = resourceFolderRoot.toPath();
    Preconditions.checkArgument(resDir != null);
    return create(resDir, resourceFolderResources, ResourceNamespace.RES_AUTO, libraryName, cachingData);
  }

  @NotNull
  private static AarSourceResourceRepository create(@NotNull Path resourceDirectoryOrFile,
                                                    @Nullable Collection<PathString> resourceFilesAndFolders,
                                                    @NotNull ResourceNamespace namespace,
                                                    @NotNull String libraryName,
                                                    @Nullable CachingData cachingData) {
    Loader loader = new Loader(resourceDirectoryOrFile, resourceFilesAndFolders, namespace);
    AarSourceResourceRepository repository = new AarSourceResourceRepository(loader, libraryName);

    // If loading from an AAR file, try to load from a cache file first.
    if (cachingData != null && resourceFilesAndFolders == null && repository.loadFromPersistentCache(cachingData)) {
      return repository;
    }

    loader.loadRepositoryContents(repository);

    repository.populatePublicResourcesMap();
    repository.freezeResources();

    if (cachingData != null && resourceFilesAndFolders == null) {
      Executor executor = cachingData.getCacheCreationExecutor();
      if (executor != null) {
        executor.execute(() -> repository.createPersistentCache(cachingData));
      }
    }
    return repository;
  }

  @Override
  @NotNull
  public Path getOrigin() {
    return myResourceDirectoryOrFile;
  }

  @TestOnly
  @NotNull
  public static AarSourceResourceRepository createForTest(
      @NotNull Path resourceDirectoryOrFile, @NotNull ResourceNamespace namespace, @NotNull String libraryName) {
    return create(resourceDirectoryOrFile, null, namespace, libraryName, null);
  }

  @Override
  @Nullable
  public String getPackageName() {
    String packageName = myNamespace.getPackageName();
    return packageName == null ? myManifestPackageName.getValue() : packageName;
  }

  @Override
  @NotNull
  public PathString getSourceFile(@NotNull String relativeResourcePath, boolean forFileResource) {
    return new PathString(mySourceFileProtocol, myResourcePathPrefix + relativeResourcePath);
  }

  @Override
  @NotNull
  public String getResourceUrl(@NotNull String relativeResourcePath) {
    return myResourceUrlPrefix + relativeResourcePath;
  }

  /**
   * Loads the resource repository from a binary cache file on disk.
   *
   * @return true if the repository was loaded from the cache, or false if the cache does not
   *     exist or is out of date
   * @see #createPersistentCache(CachingData)
   */
  private boolean loadFromPersistentCache(@NotNull CachingData cachingData) {
    byte[] header = ResourceSerializationUtil.getCacheFileHeader(stream -> writeCacheHeaderContent(cachingData, stream));
    return loadFromPersistentCache(cachingData.getCacheFile(), header);
  }

  /**
   * Creates persistent cache on disk for faster loading later.
   */
  private void createPersistentCache(@NotNull CachingData cachingData) {
    byte[] header = ResourceSerializationUtil.getCacheFileHeader(stream -> writeCacheHeaderContent(cachingData, stream));
    ResourceSerializationUtil.createPersistentCache(cachingData.getCacheFile(), header, stream -> writeToStream(stream, config -> true));
  }

  protected void writeCacheHeaderContent(@NotNull CachingData cachingData, @NotNull Base128OutputStream stream) throws IOException {
    stream.write(CACHE_FILE_HEADER);
    stream.writeString(CACHE_FILE_FORMAT_VERSION);
    stream.writeString(myResourceDirectoryOrFile.toString());
    stream.writeString(cachingData.getContentVersion());
    stream.writeString(cachingData.getCodeVersion());
  }

  /**
   * Loads contents the repository from a cache file on disk.
   * @see ResourceSerializationUtil#createPersistentCache
   */
  private boolean loadFromPersistentCache(@NotNull Path cacheFile, @NotNull byte[] fileHeader) {
    try (Base128InputStream stream = new Base128InputStream(cacheFile)) {
      if (!stream.validateContents(fileHeader)) {
        return false; // Cache file header doesn't match.
      }
      loadFromStream(stream, Maps.newHashMapWithExpectedSize(1000), null);

      populatePublicResourcesMap();
      freezeResources();
      myLoadedFromCache = true;
      return true;
    }
    catch (NoSuchFileException e) {
      return false; // Cache file does not exist.
    }
    catch (ProcessCanceledException e) {
      cleanupAfterFailedLoadingFromCache();
      throw e;
    }
    catch (Throwable e) {
      cleanupAfterFailedLoadingFromCache();
      LOG.warning("Failed to load resources from cache file " + cacheFile + " " + e);
      return false;
    }
  }

  /**
   * Called when an attempt to load from persistent cache fails after some data may have already been loaded.
   */
  protected void cleanupAfterFailedLoadingFromCache() {
    myResources.clear();  // Remove partially loaded data.
  }

  /**
   * Writes contents of the repository to the given output stream.
   *
   * @param stream the stream to write to
   * @param configFilter only resources belonging to configurations satisfying this filter are written to the stream
   */
  void writeToStream(@NotNull Base128OutputStream stream, @NotNull Predicate<FolderConfiguration> configFilter) throws IOException {
    ResourceSerializationUtil.writeResourcesToStream(myResources, stream, configFilter);
  }

  /**
   * Loads contents the repository from the given input stream.
   * @see #writeToStream(Base128OutputStream, Predicate)
   */
  protected void loadFromStream(@NotNull Base128InputStream stream,
                                @NotNull Map<String, String> stringCache,
                                @Nullable Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache) throws IOException {
    ResourceSerializationUtil.readResourcesFromStream(stream, stringCache, namespaceResolverCache, this, this::addResourceItem);
  }

  @TestOnly
  boolean isLoadedFromCache() {
    return myLoadedFromCache;
  }

  // For debugging only.
  @Override
  @NotNull
  public String toString() {
    return getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(this)) + " for " + myResourceDirectoryOrFile;
  }

  private static class Loader extends RepositoryLoader<AarSourceResourceRepository> {
    @NotNull private Set<String> myRTxtIds = ImmutableSet.of();

    Loader(@NotNull Path resourceDirectoryOrFile, @Nullable Collection<PathString> resourceFilesAndFolders,
           @NotNull ResourceNamespace namespace) {
      super(resourceDirectoryOrFile, resourceFilesAndFolders, namespace);
    }

    @Override
    protected boolean loadIdsFromRTxt() {
      if (myZipFile == null) {
        Path rDotTxt = myResourceDirectoryOrFile.resolveSibling(FN_RESOURCE_TEXT);
        if (Files.exists(rDotTxt)) {
          try {
            SymbolTable symbolTable = SymbolIo.readFromAaptNoValues(rDotTxt.toFile(), null);
            myRTxtIds = computeIds(symbolTable);
            return true;
          }
          catch (ProcessCanceledException e) {
            throw e;
          }
          catch (Exception e) {
            LOG.warning("Failed to load id resources from " + rDotTxt + " " + e);
          }
        }
      }
      else {
        ZipEntry zipEntry = myZipFile.getEntry(FN_RESOURCE_TEXT);
        if (zipEntry != null) {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(myZipFile.getInputStream(zipEntry), UTF_8))) {
            SymbolTable symbolTable = SymbolIo.readFromAaptNoValues(reader, FN_RESOURCE_TEXT + " in " + myResourceDirectoryOrFile, null);
            myRTxtIds = computeIds(symbolTable);
            return true;
          }
          catch (ProcessCanceledException e) {
            throw e;
          }
          catch (Exception e) {
            LOG.warning("Failed to load id resources from " + FN_RESOURCE_TEXT + " in " + myResourceDirectoryOrFile + " " + e);
          }
        }
        return false;
      }
      return false;
    }

    @Override
    protected void finishLoading(@NotNull AarSourceResourceRepository repository) {
      super.finishLoading(repository);
      createResourcesForRTxtIds(repository);
    }

    /**
     * Creates ID resources for the ID names in the R.txt file.
     */
    private void createResourcesForRTxtIds(@NotNull AarSourceResourceRepository repository) {
      if (!myRTxtIds.isEmpty()) {
        RepositoryConfiguration configuration = getConfiguration(repository, ResourceItem.DEFAULT_CONFIGURATION);
        ResourceSourceFile sourceFile = new ResourceSourceFileImpl(null, configuration);
        for (String name : myRTxtIds) {
          addIdResourceItem(name, sourceFile);
        }
        addValueFileResources();
      }
    }

    private static Set<String> computeIds(@NotNull SymbolTable symbolTable) {
      return symbolTable.getSymbols()
        .row(ResourceType.ID)
        .values()
        .stream()
        .map(Symbol::getCanonicalName)
        .collect(Collectors.toSet());
    }

    @Override
    protected void loadPublicResourceNames() {
      if (myZipFile == null) {
        Path file = myResourceDirectoryOrFile.resolveSibling(FN_PUBLIC_TXT);
        try (BufferedReader reader = Files.newBufferedReader(file)) {
          readPublicResourceNames(reader);
        }
        catch (NoSuchFileException e) {
          myDefaultVisibility = ResourceVisibility.PUBLIC; // The "public.txt" file does not exist - myDefaultVisibility will be PUBLIC.
        }
        catch (IOException e) {
          // Failure to load public resource names is not considered fatal.
          LOG.warning("Error reading " + file + " " + e);
        }
      } else {
        ZipEntry zipEntry = myZipFile.getEntry(FN_PUBLIC_TXT);
        if (zipEntry == null) {
          myDefaultVisibility = ResourceVisibility.PUBLIC; // The "public.txt" file does not exist - myDefaultVisibility will be PUBLIC.
        }
        else {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(myZipFile.getInputStream(zipEntry), UTF_8))) {
            readPublicResourceNames(reader);
          }
          catch (IOException e) {
            // Failure to load public resource names is not considered fatal.
            LOG.warning("Error reading " + FN_PUBLIC_TXT + " from " + myResourceDirectoryOrFile + " " + e);
          }
        }
      }
    }

    @Override
    protected void addResourceItem(@NotNull BasicResourceItem item, @NotNull AarSourceResourceRepository repository) {
      repository.addResourceItem(item);
    }

    private void readPublicResourceNames(@NotNull BufferedReader reader) throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        // Lines in public.txt have the following format: <resource_type> <resource_name>
        line = line.trim();
        int delimiterPos = line.indexOf(' ');
        if (delimiterPos > 0 && delimiterPos + 1 < line.length()) {
          ResourceType type = ResourceType.fromXmlTagName(line.substring(0, delimiterPos));
          if (type != null) {
            String name = line.substring(delimiterPos + 1);
            addPublicResourceName(type, name);
          }
        }
      }
    }
  }
}
