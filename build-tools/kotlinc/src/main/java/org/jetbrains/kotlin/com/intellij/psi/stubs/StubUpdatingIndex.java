package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageParserDefinitions;
import org.jetbrains.kotlin.com.intellij.lang.ParserDefinition;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Attachment;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.PushedFilePropertiesRetriever;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.DebugUtil;
import org.jetbrains.kotlin.com.intellij.psi.tree.IFileElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IStubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.CustomImplementationFileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexedFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.PsiDependentFileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.SingleEntryCompositeIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.SingleEntryFileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.SingleEntryIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.SubstitutedFileType;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.VfsAwareIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndexMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.TransientChangesIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.MapReduceIndexBase;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorStringDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentHashMapValueStorage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class StubUpdatingIndex extends SingleEntryFileBasedIndexExtension<SerializedStubTree> implements CustomImplementationFileBasedIndexExtension<Integer, SerializedStubTree> {
    //  @ApiStatus.Internal
    static final Logger LOG = Logger.getInstance(StubUpdatingIndex.class);
    private static final boolean DEBUG_PREBUILT_INDICES =
            SystemProperties.getBooleanProperty("debug.prebuilt.indices", false);

    public static final boolean USE_SNAPSHOT_MAPPINGS = false; //TODO

    private static final int VERSION =
            45 + (PersistentHashMapValueStorage.COMPRESSION_ENABLED ? 1 : 0);

    public static final ID<Integer, SerializedStubTree> INDEX_ID = ID.create("Stubs");

    @NonNull
    private final StubForwardIndexExternalizer<?> myStubIndexesExternalizer;

    @NonNull
    private final SerializationManagerEx mySerializationManager;

    public StubUpdatingIndex() {
        this(StubForwardIndexExternalizer.getIdeUsedExternalizer(),
                SerializationManagerEx.getInstanceEx());
    }

    public StubUpdatingIndex(@NonNull StubForwardIndexExternalizer<?> stubIndexesExternalizer,
                             @NonNull SerializationManagerEx serializationManager) {
        myStubIndexesExternalizer = stubIndexesExternalizer;
        mySerializationManager = serializationManager;
    }

    public static boolean canHaveStub(@NonNull VirtualFile file) {
        Project project = ProjectCoreUtil.theOnlyOpenProject();
        FileType fileType =
                SubstitutedFileType.substituteFileType(file, file.getFileType(), project);
        return canHaveStub(file, fileType);
    }

    private static boolean canHaveStub(@NonNull VirtualFile file, @NonNull FileType fileType) {
        if (fileType instanceof LanguageFileType) {
            Language l = ((LanguageFileType) fileType).getLanguage();
            ParserDefinition parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(l);
            FileBasedIndexEx fileBasedIndex =
                    ObjectUtils.tryCast(FileBasedIndex.getInstance(), FileBasedIndexEx.class);

            if (parserDefinition == null) {
                if (fileBasedIndex != null && fileBasedIndex.doTraceStubUpdates(INDEX_ID)) {
                    fileBasedIndex.getLogger().info("No parser definition for " + file.getName());
                }
                return false;
            }

            final IFileElementType elementType = parserDefinition.getFileNodeType();
            if (elementType instanceof IStubFileElementType &&
                ((IStubFileElementType<?>) elementType).shouldBuildStubFor(file)) {
                if (fileBasedIndex != null && fileBasedIndex.doTraceStubUpdates(INDEX_ID)) {
                    fileBasedIndex.getLogger().info("Should build stub for " + file.getName());
                }
                return true;
            }

            if (fileBasedIndex != null && fileBasedIndex.doTraceStubUpdates(INDEX_ID)) {
                fileBasedIndex.getLogger()
                        .info("Can't build stub using stub file element type " +
                              file.getName() +
                              ", properties: " +
                              PushedFilePropertiesRetriever.getInstance()
                                      .dumpSortedPushedProperties(file));
            }
        }
        final BinaryFileStubBuilder builder = BinaryFileStubBuilders.INSTANCE.forFileType(fileType);
        return builder != null && builder.acceptsFile(file);
    }

    @NonNull
    @Override
    public ID<Integer, SerializedStubTree> getName() {
        return INDEX_ID;
    }

    @NonNull
    @Override
    public SingleEntryIndexer<SerializedStubTree> getIndexer() {
        return new SingleEntryCompositeIndexer<SerializedStubTree, StubBuilderType, String>(false) {
            @Override
            public boolean requiresContentForSubIndexerEvaluation(@NonNull IndexedFile file) {
                return StubTreeBuilder.requiresContentToFindBuilder(file.getFileType());
            }

            @Nullable
            @Override
            public StubBuilderType calculateSubIndexer(@NonNull IndexedFile file) {
                return StubTreeBuilder.getStubBuilderType(file, true);
            }

            @NonNull
            @Override
            public String getSubIndexerVersion(@NonNull StubBuilderType type) {
                mySerializationManager.initSerializers();
                return String.valueOf(type.getBinaryFileStubBuilder().getStubVersion());
            }

            @NonNull
            @Override
            public KeyDescriptor<String> getSubIndexerVersionDescriptor() {
                return EnumeratorStringDescriptor.INSTANCE;
            }

            @Override
            protected @Nullable SerializedStubTree computeValue(@NonNull FileContent inputData) {
                StubBuilderType subIndexerType = calculateSubIndexer(inputData);
                if (subIndexerType == null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Stub builder not found for " +
                                  inputData.getFile() +
                                  ", " +
                                  calculateIndexingStamp(inputData));
                    }
                    return null;
                }
                return computeValue(inputData, Objects.requireNonNull(subIndexerType));
            }

            @Override
            @Nullable
            protected SerializedStubTree computeValue(@NonNull final FileContent inputData,
                                                      @NonNull StubBuilderType type) {
                try {
                    SerializedStubTree prebuiltTree = findPrebuiltSerializedStubTree(inputData);
                    if (prebuiltTree != null) {
                        prebuiltTree = prebuiltTree.reSerialize(mySerializationManager,
                                myStubIndexesExternalizer);
                        if (DEBUG_PREBUILT_INDICES) {
                            assertPrebuiltStubTreeMatchesActualTree(prebuiltTree, inputData, type);
                        }
                        return prebuiltTree;
                    }
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception e) {
                    LOG.error("Error while indexing: " +
                              inputData.getFileName() +
                              " using prebuilt stub index", e);
                }

                Stub stub;
                try {
                    stub = StubTreeBuilder.buildStubTree(inputData, type);
                } catch (Exception e) {
                    if (e instanceof ControlFlowException) {
                        ExceptionUtil.rethrowUnchecked(e);
                    }
                    throw new MapReduceIndexMappingException(e, type.getClass());
                }
                if (stub == null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("No stub present for " +
                                  inputData.getFile() +
                                  ", " +
                                  calculateIndexingStamp(inputData));
                    }
                    return null;
                }

                SerializedStubTree serializedStubTree;
                try {
                    serializedStubTree = SerializedStubTree.serializeStub(stub,
                            mySerializationManager,
                            myStubIndexesExternalizer);
                    if (IndexDebugProperties.DEBUG) {
                        assertDeserializedStubMatchesOriginalStub(serializedStubTree, stub);
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Stub is built for " +
                                  inputData.getFile() +
                                  ", " +
                                  calculateIndexingStamp(inputData));
                    }
                } catch (Exception e) {
                    if (e instanceof ControlFlowException) {
                        ExceptionUtil.rethrowUnchecked(e);
                    }
                    ObjectStubSerializer<?, ? extends Stub> stubType = stub.getStubType();
                    Class<?> classToBlame =
                            stubType != null ? stubType.getClass() : stub.getClass();
                    throw new MapReduceIndexMappingException(e, classToBlame);
                }
                return serializedStubTree;
            }
        };
    }

//  private static final FileTypeExtension<PrebuiltStubsProvider> PREBUILT_STUBS_PROVIDER_EP =
//    new FileTypeExtension<>("com.intellij.filetype.prebuiltStubsProvider");

    @Nullable
    private static SerializedStubTree findPrebuiltSerializedStubTree(@NonNull FileContent fileContent) {
//    PrebuiltStubsProvider prebuiltStubsProvider = PREBUILT_STUBS_PROVIDER_EP.forFileType
//    (fileContent.getFileType());
//    if (prebuiltStubsProvider == null) {
//      return null;
//    }
//    return prebuiltStubsProvider.findStub(fileContent);
        return null;
    }

    private static void assertDeserializedStubMatchesOriginalStub(@NonNull SerializedStubTree stubTree,
                                                                  @NonNull Stub originalStub) {
        Stub deserializedStub;
        try {
            deserializedStub = stubTree.getStub();
        } catch (SerializerNotFoundException e) {
            throw new RuntimeException("Failed to deserialize stub tree", e);
        }
        if (!areStubsSimilar(originalStub, deserializedStub)) {
            LOG.error("original and deserialized trees are not the same",
                    new Attachment("originalStub.txt", DebugUtil.stubTreeToString(originalStub)),
                    new Attachment("deserializedStub.txt",
                            DebugUtil.stubTreeToString(deserializedStub)));
        }
    }

    private static boolean areStubsSimilar(@NonNull Stub stub, @NonNull Stub stub2) {
        if (stub.getStubType() != stub2.getStubType()) {
            return false;
        }
        List<? extends Stub> stubs = stub.getChildrenStubs();
        List<? extends Stub> stubs2 = stub2.getChildrenStubs();

        if (stubs.size() != stubs2.size()) {
            return false;
        }

        for (int i = 0, len = stubs.size(); i < len; ++i) {
            if (!areStubsSimilar(stubs.get(i), stubs2.get(i))) {
                return false;
            }
        }

        return true;
    }

    private void assertPrebuiltStubTreeMatchesActualTree(@NonNull SerializedStubTree prebuiltStubTree,
                                                         @NonNull FileContent fileContent,
                                                         @NonNull StubBuilderType type) {
        try {
            Stub stub = StubTreeBuilder.buildStubTree(fileContent, type);
            if (stub == null) {
                return;
            }
            SerializedStubTree actualTree = SerializedStubTree.serializeStub(stub,
                    mySerializationManager,
                    myStubIndexesExternalizer);
//      if (!IndexDataComparer.INSTANCE.areStubTreesTheSame(actualTree, prebuiltStubTree)) {
//        throw new RuntimeExceptionWithAttachments(
//          "Prebuilt stub tree does not match actual stub tree",
//          new Attachment("actual-stub-tree.txt", IndexDataPresenter.INSTANCE
//          .getPresentableSerializedStubTree(actualTree)),
//          new Attachment("prebuilt-stub-tree.txt", IndexDataPresenter.INSTANCE
//          .getPresentableSerializedStubTree(prebuiltStubTree))
//        );
//      }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    static IndexingStampInfo calculateIndexingStamp(@NonNull FileContent content) {
        VirtualFile file = content.getFile();
        boolean isBinary = file.getFileType().isBinary();

        int contentLength = -1;
        if (!isBinary && content instanceof PsiDependentFileContent) {
            contentLength = ((PsiDependentFileContent) content).getPsiFile().getTextLength();
        }

        long byteLength = file.getLength();
        return new IndexingStampInfo(file.getTimeStamp(), byteLength, contentLength, isBinary);
    }

    @Override
    public int getCacheSize() {
        return super.getCacheSize() * Runtime.getRuntime().availableProcessors();
    }

    @NonNull
    @Override
    public DataExternalizer<SerializedStubTree> getValueExternalizer() {
        return new SerializedStubTreeDataExternalizer(mySerializationManager,
                myStubIndexesExternalizer);
    }

    @NonNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return (FileBasedIndex.ProjectSpecificInputFilter) file -> canHaveStub(file.getFile(),
                file.getFileType());
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public boolean enableWal() {
        return true;
    }

    @Override
    public void handleInitializationError(@NonNull Throwable e) {
        ((StubIndexEx) StubIndex.getInstance()).initializationFailed(e);
    }

    @NonNull
    @Override
    public UpdatableIndex<Integer, SerializedStubTree, FileContent, ?> createIndexImplementation(@NonNull final FileBasedIndexExtension<Integer, SerializedStubTree> extension,
                                                                                                 @NonNull VfsAwareIndexStorageLayout<Integer, SerializedStubTree> layout) throws StorageException, IOException {
        ((StubIndexEx) StubIndex.getInstance()).initializeStubIndexes();
        checkNameStorage();
        mySerializationManager.initialize();

        MapReduceIndexBase<Integer, SerializedStubTree, ?> index =
                StubUpdatableIndexFactory.getInstance()
                        .createIndex(extension, new VfsAwareIndexStorageLayout<Integer, SerializedStubTree>() {
                            @Override
                            public void clearIndexData() {
                                layout.clearIndexData();
                            }

                            @Override
                            public @NonNull IndexStorage<Integer, SerializedStubTree> openIndexStorage() throws IOException {
                                return layout.openIndexStorage();
                            }

                            @Override
                            public @Nullable ForwardIndex openForwardIndex() throws IOException {
                                return layout.openForwardIndex();
                            }

                            @Override
                            public @NonNull ForwardIndexAccessor<Integer, SerializedStubTree> getForwardIndexAccessor() {
                                return new StubUpdatingForwardIndexAccessor(extension);
                            }
                        }, mySerializationManager);
        if (index.getStorage() instanceof TransientChangesIndexStorage) {
            TransientChangesIndexStorage<Integer, SerializedStubTree> memStorage =
                    (TransientChangesIndexStorage<Integer, SerializedStubTree>) index.getStorage();
            memStorage.addBufferingStateListener(new TransientChangesIndexStorage.BufferingStateListener() {
                @Override
                public void bufferingStateChanged(final boolean newState) {
                    ((StubIndexEx) StubIndex.getInstance()).setDataBufferingEnabled(newState);
                }

                @Override
                public void memoryStorageCleared() {
                    ((StubIndexEx) StubIndex.getInstance()).cleanupMemoryStorage();
                }
            });
        }
        return index;
    }

    private void checkNameStorage() throws StorageException {
        if (mySerializationManager.isNameStorageCorrupted()) {
            StorageException exception =
                    new StorageException("NameStorage for stubs serialization has been corrupted");
            mySerializationManager.repairNameStorage(exception);
            throw exception;
        }
    }
}
