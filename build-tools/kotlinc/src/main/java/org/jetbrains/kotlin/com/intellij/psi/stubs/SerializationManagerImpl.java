package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.kotlin.com.intellij.lang.LanguageParserDefinitions;
import org.jetbrains.kotlin.com.intellij.lang.ParserDefinition;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyedExtensionCollector;
import org.jetbrains.kotlin.com.intellij.openapi.util.ShutDownTracker;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IStubFileElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.StubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.io.DataEnumeratorEx;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.com.intellij.util.io.InMemoryDataEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentStringEnumerator;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.jetbrains.kotlin.com.intellij.util.io.PersistentHashMapValueStorage.CreationTimeOptions;
import static java.util.Comparator.comparing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// todo rewrite: it's an app service for now but its lifecycle should be synchronized with stub index.
//@ApiStatus.Internal
public final class SerializationManagerImpl extends SerializationManagerEx implements Disposable {
  private static final Logger LOG = Logger.getInstance(SerializationManagerImpl.class);

  private final AtomicBoolean myNameStorageCrashed = new AtomicBoolean();
  private final @NonNull Supplier<? extends Path> myFile;
  private final boolean myUnmodifiable;
  private final AtomicBoolean myInitialized = new AtomicBoolean();

  private volatile Path myOpenFile;
  private volatile StubSerializationHelper myStubSerializationHelper;
  private volatile StubSerializerEnumerator mySerializerEnumerator;
  private volatile boolean mySerializersLoaded;

  public SerializationManagerImpl() {
    this(() -> FileBasedIndex.USE_IN_MEMORY_INDEX ? null : PathManager.getIndexRoot().toPath().resolve("rep.names"), false);
  }

//  @NonInjectable
  public SerializationManagerImpl(@NonNull Path nameStorageFile, boolean unmodifiable) {
    this(() -> nameStorageFile, unmodifiable);
  }

//  @NonInjectable
  public SerializationManagerImpl(@NonNull Supplier<? extends Path> nameStorageFile, boolean unmodifiable) {
    myFile = nameStorageFile;
    myUnmodifiable = unmodifiable;
    try {
      initialize();
    }
    finally {
      if (!unmodifiable) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::performShutdown));
      }
    }

    StubElementTypeHolderEP.EP_NAME.addChangeListener(this::dropSerializerData, this);
  }

  @Override
  public void initialize() {
      if (myInitialized.get()) {
          return;
      }
    doInitialize();
  }

  private void doInitialize() {
    try {
      // we need to cache last id -> String mappings due to StringRefs and stubs indexing that initially creates stubs (doing enumerate on String)
      // and then index them (valueOf), also similar string items are expected to be enumerated during stubs processing
      StubSerializerEnumerator enumerator = new StubSerializerEnumerator(openNameStorage(), myUnmodifiable);
      mySerializerEnumerator = enumerator;
      myStubSerializationHelper = new StubSerializationHelper(enumerator);
    }
    catch (IOException e) {
      nameStorageCrashed();
      LOG.info(e);
    }
    finally {
      myInitialized.set(true);
    }
  }

  @NonNull
  private DataEnumeratorEx<String> openNameStorage() throws IOException {
    myOpenFile = myFile.get();
    if (myOpenFile == null) {
      return new InMemoryDataEnumerator<>();
    }

    return CreationTimeOptions.threadLocalOptions()
      .readOnly(myUnmodifiable)
      .with(() -> new PersistentStringEnumerator(myOpenFile, /*cacheLastMapping: */ true));
  }

  @ApiStatus.Internal
  public Map<String, Integer> dumpNameStorage() {
    return mySerializerEnumerator.dump();
  }

  @Override
  public boolean isNameStorageCorrupted() {
    return myNameStorageCrashed.get();
  }

  @Override
  public void repairNameStorage(@NonNull Exception corruptionCause) {
    if (myNameStorageCrashed.getAndSet(false)) {
      if (myUnmodifiable) {
        LOG.error("Data provided by unmodifiable serialization manager can be invalid after repair");
      }
      LOG.info("Name storage is repaired");

      StubSerializerEnumerator enumerator = mySerializerEnumerator;
      if (enumerator != null) {
        try {
          enumerator.close();
        }
        catch (Exception ignored) {}
      }
      if (myOpenFile != null) {
        IOUtil.deleteAllFilesStartingWith(myOpenFile);
      }
      doInitialize();
    }
  }

  @Override
  public void flushNameStorage() throws IOException {
    mySerializerEnumerator.flush();
  }

  private void registerSerializer(ObjectStubSerializer<?, ? extends Stub> serializer) {
    registerSerializer(serializer.getExternalId(), () -> serializer);
  }

  @Override
  public void reinitializeNameStorage() {
    nameStorageCrashed();
    repairNameStorage(new Exception("Indexes are requested to rebuild"));
  }

  private void nameStorageCrashed() {
    myNameStorageCrashed.set(true);
  }

  @Override
  public void dispose() {
    performShutdown();
  }

  @Override
  public void performShutdown() {
    if (!myInitialized.compareAndSet(true, false)) {
      return; // already shut down
    }
    String name = myOpenFile != null ? myOpenFile.toString() : "in-memory storage";
    if (!myUnmodifiable) {
      LOG.info("Start shutting down " + name);
    }
    try {
      mySerializerEnumerator.close();
      if (!myUnmodifiable) {
        LOG.info("Finished shutting down " + name);
      }
    }
    catch (IOException e) {
      nameStorageCrashed();
      LOG.error(e);
    }
  }

  private void registerSerializer(@NonNull String externalId, @NonNull Supplier<? extends ObjectStubSerializer<?, ? extends Stub>> lazySerializer) {
    try {
      mySerializerEnumerator.assignId(lazySerializer, externalId);
    }
    catch (IOException e) {
      LOG.info(e);
      nameStorageCrashed();
    }
  }

  @Override
  public void serialize(@NonNull Stub rootStub, @NonNull OutputStream stream) {
    initSerializers();
    try {
      myStubSerializationHelper.serialize(rootStub, stream);
    }
    catch (IOException e) {
      LOG.info(e);
      nameStorageCrashed();
    }
  }

  @NonNull
  @Override
  public Stub deserialize(@NonNull InputStream stream) throws SerializerNotFoundException {
    initSerializers();

    try {
      return myStubSerializationHelper.deserialize(stream);
    }
    catch (IOException e) {
      nameStorageCrashed();
      LOG.info(e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void reSerialize(@NonNull InputStream inStub,
                          @NonNull OutputStream outStub,
                          @NonNull StubTreeSerializer newSerializationManager) throws IOException {
    initSerializers();
    ((SerializationManagerEx)newSerializationManager).initSerializers();
    myStubSerializationHelper.reSerializeStub(new DataInputStream(inStub),
                                              new DataOutputStream(outStub),
                                              ((SerializationManagerImpl)newSerializationManager).myStubSerializationHelper);
  }

  @Override
  protected void initSerializers() {
      if (mySerializersLoaded) {
          return;
      }
    //noinspection SynchronizeOnThis
    synchronized (this) {
      if (mySerializersLoaded) {
        return;
      }

      ProgressManager.getInstance().executeNonCancelableSection(() -> {
        instantiateElementTypesFromFields();
        StubIndexEx.initExtensions();
      });

      registerSerializer(PsiFileStubImpl.TYPE);

      final List<StubFieldAccessor> lazySerializers =  Collections.emptyList();// IStubElementType.loadRegisteredStubElementTypes();

      final IElementType[] stubElementTypes = IElementType.enumerate(type -> type instanceof StubSerializer);
      Arrays.sort(
        stubElementTypes,         
        comparing((IElementType type) -> type.getLanguage().getID())
          //TODO RC: not sure .debugName is enough for stable sorting. Maybe use .getClass() instead?
          .thenComparing(IElementType::getDebugName)
      );
      for (IElementType type : stubElementTypes) {
        if (type instanceof StubFileElementType &&
            StubFileElementType.DEFAULT_EXTERNAL_ID.equals(((StubFileElementType<?>)type).getExternalId())) {
          continue;
        }

        registerSerializer((StubSerializer<?>)type);
      }

      final List<StubFieldAccessor> sortedLazySerializers = lazySerializers.stream()
        //TODO RC: is .externalId enough for stable sorting? Seems like .myField is also important,
        //         but it should also be dependent on .externalId...
        .sorted(comparing(sfa -> sfa.externalId))
        .collect(Collectors.toList());
      for (StubFieldAccessor lazySerializer : sortedLazySerializers) {
        registerSerializer(lazySerializer.externalId, lazySerializer);
      }
      mySerializersLoaded = true;
    }
  }

  @NonNull ObjectStubSerializer<?, ? extends Stub> getSerializer(@NonNull String name) throws SerializerNotFoundException {
    return mySerializerEnumerator.getSerializer(name);
  }

  @Nullable
  public String getSerializerName(@NonNull ObjectStubSerializer<?, ? extends Stub> serializer) {
    return mySerializerEnumerator.getSerializerName(serializer);
  }

  public void dropSerializerData() {
    //noinspection SynchronizeOnThis
    synchronized (this) {
//      new IStubElementType<>();
      IStubFileElementType.dropTemplateStubBaseVersion();
      StubSerializerEnumerator enumerator = mySerializerEnumerator;
      if (enumerator != null) {
        enumerator.dropRegisteredSerializers();
      }
      else {
        // has been corrupted previously
        nameStorageCrashed();
      }
      mySerializersLoaded = false;
    }
  }

  private static void instantiateElementTypesFromFields() {
    // load stub serializers before usage
    FileTypeRegistry.getInstance().getRegisteredFileTypes();
    getExtensions(BinaryFileStubBuilders.INSTANCE, builder -> {});
    getExtensions(LanguageParserDefinitions.INSTANCE, ParserDefinition::getFileNodeType);
  }

  private static <T> void getExtensions(@NonNull KeyedExtensionCollector<T, ?> collector, @NonNull Consumer<? super T> consumer) {
    ExtensionPointImpl<KeyedLazyInstance<T>> point = (ExtensionPointImpl<KeyedLazyInstance<T>>)collector.getPoint();
    if (point != null) {
      for (KeyedLazyInstance<T> instance : point) {
        consumer.accept(instance.getInstance());
      }
    }
  }
}
