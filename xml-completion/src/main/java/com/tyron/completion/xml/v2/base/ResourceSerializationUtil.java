package com.tyron.completion.xml.v2.base;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleItemResourceValue;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;
import com.android.utils.Base128InputStream;
import com.android.utils.Base128OutputStream;
import com.google.common.collect.ListMultimap;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.progress.ProgressManager;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtilRt;

/**
 * Static methods for serialization and deserialization of resources implementing {@link BasicResourceItem} interface.
 */
public class ResourceSerializationUtil {
  private static final Logger LOG = IdeLog.getCurrentLogger(ResourceSerializationUtil.class);

  /**
   * Writes contents of a resource repository to a cache file on disk.
   *
   * The data is stored as follows:
   * <ol>
   *   <li>The header provided by the caller (sequence of bytes)</li>
   *   <li>Number of folder configurations (int)</li>
   *   <li>Qualifier strings of folder configurations (strings)</li>
   *   <li>Number of value resource files (int)</li>
   *   <li>Value resource files (see {@link ResourceSourceFile#serialize})</li>
   *   <li>Number of namespace resolvers (int)</li>
   *   <li>Serialized namespace resolvers (see {@link NamespaceResolver#serialize})</li>
   *   <li>Number of resource items (int)</li>
   *   <li>Serialized resource items (see {@link BasicResourceItemBase#serialize})</li>
   * </ol>
   */
  public static void createPersistentCache(@NotNull Path cacheFile, @NotNull byte[] fileHeader,
                                           @NotNull Base128StreamWriter contentWriter) {
    // Try to delete the old cache file.
    try {
      Files.deleteIfExists(cacheFile);
    }
    catch (IOException e) {
      LOG.warning("Unable to delete " + cacheFile + " " + e);
    }

    // Write to a temporary file first, then rename it to the final name.
    Path tempFile;
    try {
      tempFile = FileUtilRt.createTempFile(cacheFile.getParent().toFile(), cacheFile.getFileName().toString(), ".tmp").toPath();
    }
    catch (IOException e) {
      LOG.severe("Unable to create a temporary file in " + cacheFile.getParent().toString() + "\n" + e);
      return;
    }

    try (Base128OutputStream stream = new Base128OutputStream(tempFile)) {
      stream.write(fileHeader);
      contentWriter.write(stream);
    }
    catch (Throwable e) {
      LOG.severe("Unable to create cache file " + tempFile + " " + e);
      deleteIgnoringErrors(tempFile);
      return;
    }

    try {
      Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (NoSuchFileException e) {
      // Ignore. This may happen in tests if the "caches" directory was cleaned up by a test tear down.
    } catch (IOException e) {
      LOG.severe("Unable to create cache file " + cacheFile + " " + e);
      deleteIgnoringErrors(tempFile);
    }
  }

  /**
   * Writes resources to the given output stream.
   *
   * @param resources the resources to write
   * @param stream the stream to write to
   * @param configFilter only resources belonging to configurations satisfying this filter are written to the stream
   */
  public static void writeResourcesToStream(@NotNull Map<ResourceType, ListMultimap<String, ResourceItem>> resources,
                                            @NotNull Base128OutputStream stream,
                                            @NotNull Predicate<FolderConfiguration> configFilter) throws IOException {
    Object2IntMap<String> qualifierStringIndexes = new Object2IntOpenHashMap<>();
    qualifierStringIndexes.defaultReturnValue(-1);
    Object2IntMap<ResourceSourceFile> sourceFileIndexes = new Object2IntOpenHashMap<>();
    sourceFileIndexes.defaultReturnValue(-1);
    Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes = new Object2IntOpenHashMap<>();
    namespaceResolverIndexes.defaultReturnValue(-1);
    int itemCount = 0;
    Collection<ListMultimap<String, ResourceItem>> resourceMaps = resources.values();

    for (ListMultimap<String, ResourceItem> resourceMap : resourceMaps) {
      for (ResourceItem item : resourceMap.values()) {
        FolderConfiguration configuration = item.getConfiguration();
        if (configFilter.test(configuration)) {
          String qualifier = configuration.getQualifierString();
          if (!qualifierStringIndexes.containsKey(qualifier)) {
            qualifierStringIndexes.put(qualifier, qualifierStringIndexes.size());
          }
          if (item instanceof BasicValueResourceItemBase) {
            ResourceSourceFile sourceFile = ((BasicValueResourceItemBase)item).getSourceFile();
            if (!sourceFileIndexes.containsKey(sourceFile)) {
              sourceFileIndexes.put(sourceFile, sourceFileIndexes.size());
            }
          }
          if (item instanceof ResourceValue) {
            addToNamespaceResolverIndexes(((ResourceValue)item).getNamespaceResolver(), namespaceResolverIndexes);
          }
          if (item instanceof BasicStyleResourceItem) {
            for (StyleItemResourceValue styleItem : ((BasicStyleResourceItem)item).getDefinedItems()) {
              addToNamespaceResolverIndexes(styleItem.getNamespaceResolver(), namespaceResolverIndexes);
            }
          }
          else if (item instanceof BasicStyleableResourceItem) {
            for (AttrResourceValue attr : ((BasicStyleableResourceItem)item).getAllAttributes()) {
              addToNamespaceResolverIndexes(attr.getNamespaceResolver(), namespaceResolverIndexes);
            }
          }
          itemCount++;
        }
      }
    }

    writeStrings(qualifierStringIndexes, stream);
    writeSourceFiles(sourceFileIndexes, stream, qualifierStringIndexes);
    writeNamespaceResolvers(namespaceResolverIndexes, stream);

    stream.writeInt(itemCount);

    for (ListMultimap<String, ResourceItem> resourceMap : resourceMaps) {
      for (ResourceItem item : resourceMap.values()) {
        FolderConfiguration configuration = item.getConfiguration();
        if (configFilter.test(configuration)) {
          ((BasicResourceItemBase)item).serialize(stream, qualifierStringIndexes, sourceFileIndexes, namespaceResolverIndexes);
        }
      }
    }
  }

  private static void addToNamespaceResolverIndexes(@NotNull ResourceNamespace.Resolver resolver,
                                                    @NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes) {
    if (!namespaceResolverIndexes.containsKey(resolver)) {
      namespaceResolverIndexes.put(resolver, namespaceResolverIndexes.size());
    }
  }

  /**
   * Loads resources from the given input stream and passes then to the given consumer.
   * @see #writeResourcesToStream
   */
  public static void readResourcesFromStream(@NotNull Base128InputStream stream,
                                             @NotNull Map<String, String> stringCache,
                                             @Nullable Map<NamespaceResolver, NamespaceResolver> namespaceResolverCache,
                                             @NotNull LoadableResourceRepository repository,
                                             @NotNull Consumer<BasicResourceItem> resourceConsumer) throws IOException {
    stream.setStringCache(stringCache); // Enable string instance sharing to minimize memory consumption.

    int n = stream.readInt();
    if (n == 0) {
      return; // Nothing to load.
    }
    List<RepositoryConfiguration> configurations = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      String configQualifier = stream.readString();
      if (configQualifier == null) {
        throw Base128InputStream.StreamFormatException.invalidFormat();
      }
      FolderConfiguration folderConfig = FolderConfiguration.getConfigForQualifierString(configQualifier);
      if (folderConfig == null) {
        throw Base128InputStream.StreamFormatException.invalidFormat();
      }
      configurations.add(new RepositoryConfiguration(repository, folderConfig));
    }

    n = stream.readInt();
    List<ResourceSourceFile> newSourceFiles = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      ResourceSourceFile sourceFile = repository.deserializeResourceSourceFile(stream, configurations);
      newSourceFiles.add(sourceFile);
    }

    n = stream.readInt();
    List<ResourceNamespace.Resolver> newNamespaceResolvers = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      NamespaceResolver namespaceResolver = NamespaceResolver.deserialize(stream);
      if (namespaceResolverCache != null) {
        namespaceResolver = namespaceResolverCache.computeIfAbsent(namespaceResolver, Function.identity());
      }
      newNamespaceResolvers.add(namespaceResolver);
    }

    n = stream.readInt();
    int cancellationCheckInterval = 500; // For framework repository without locale-specific resources cancellation check happens 32 times.
    for (int i = 0; i < n; i++) {
      if (i % cancellationCheckInterval == 0) {
        ProgressManager.checkCanceled();
      }
      BasicResourceItemBase item = BasicResourceItemBase.deserialize(stream, configurations, newSourceFiles, newNamespaceResolvers);
      resourceConsumer.accept(item);
    }
  }

  /**
   * Returns contents of a cache file header produced by the given writer code.
   *
   * @param headerWriter the writer object
   * @return the cache file header contents in a byte array
   */
  public static @NotNull byte[] getCacheFileHeader(@NotNull Base128StreamWriter headerWriter) {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    try (Base128OutputStream stream = new Base128OutputStream(header)) {
      headerWriter.write(stream);
    }
    catch (IOException e) {
      throw new Error("Internal error", e); // An IOException in the try block above indicates a bug.
    }
    return header.toByteArray();
  }
  private static void deleteIgnoringErrors(@NotNull Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  private static void writeStrings(@NotNull Object2IntMap<String> qualifierStringIndexes, @NotNull Base128OutputStream stream)
      throws IOException {
    String[] strings = new String[qualifierStringIndexes.size()];
    for (Object2IntMap.Entry<String> entry : Object2IntMaps.fastIterable(qualifierStringIndexes)) {
      strings[entry.getIntValue()] = entry.getKey();
    }
    stream.writeInt(strings.length);
    for (String str : strings) {
      stream.writeString(str);
    }
  }

  private static void writeSourceFiles(@NotNull Object2IntMap<ResourceSourceFile> sourceFileIndexes,
                                       @NotNull Base128OutputStream stream,
                                       @NotNull Object2IntMap<String> qualifierStringIndexes) throws IOException {
    ResourceSourceFile[] sourceFiles = new ResourceSourceFile[sourceFileIndexes.size()];
    for (Object2IntMap.Entry<ResourceSourceFile> entry : Object2IntMaps.fastIterable(sourceFileIndexes)) {
      sourceFiles[entry.getIntValue()] = entry.getKey();
    }
    stream.writeInt(sourceFiles.length);
    for (ResourceSourceFile sourceFile : sourceFiles) {
      sourceFile.serialize(stream, qualifierStringIndexes);
    }
  }

  private static void writeNamespaceResolvers(@NotNull Object2IntMap<ResourceNamespace.Resolver> namespaceResolverIndexes,
                                              @NotNull Base128OutputStream stream) throws IOException {
    ResourceNamespace.Resolver[] resolvers = new ResourceNamespace.Resolver[namespaceResolverIndexes.size()];
    for (Object2IntMap.Entry<ResourceNamespace.Resolver> entry : Object2IntMaps.fastIterable(namespaceResolverIndexes)) {
      resolvers[entry.getIntValue()] = entry.getKey();
    }
    stream.writeInt(resolvers.length);
    for (ResourceNamespace.Resolver resolver : resolvers) {
      NamespaceResolver serializableResolver =
          resolver == ResourceNamespace.Resolver.EMPTY_RESOLVER ? NamespaceResolver.EMPTY : (NamespaceResolver)resolver;
      serializableResolver.serialize(stream);
    }
  }

  public interface Base128StreamWriter {
    void write(@NotNull Base128OutputStream stream) throws IOException;
  }
}