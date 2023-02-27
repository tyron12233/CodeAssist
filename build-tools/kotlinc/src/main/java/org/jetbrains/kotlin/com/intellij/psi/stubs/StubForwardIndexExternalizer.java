package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.containers.HashingStrategy;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.io.*;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public abstract class StubForwardIndexExternalizer<StubKeySerializationState> implements DataExternalizer<Map<StubIndexKey<?, ?>, Map<Object, StubIdList>>> {

    //  @ApiStatus.Internal
    public static final String USE_SHAREABLE_STUBS_PROP = "idea.uses.shareable.serialized.stubs";

    //  @ApiStatus.Internal
    public static final boolean USE_SHAREABLE_STUBS = Boolean.getBoolean(USE_SHAREABLE_STUBS_PROP);


    /**
     * If true -> serialization methods store values in a deterministic, stable order -- but at
     * slightly
     * higher runtime cost (i.e. additional sorting). Useful e.g. for reproducible shared indexes.
     * <br/>
     * If false (default) -> serialization methods store values in arbitrary order, but slightly
     * faster.
     */
    private final boolean useStableBinaryFormat;

    protected StubForwardIndexExternalizer() {
        this(false);
    }

    protected StubForwardIndexExternalizer(final boolean useStableBinaryFormat) {
        this.useStableBinaryFormat = useStableBinaryFormat;
    }

    @NonNull
    public static StubForwardIndexExternalizer<?> getIdeUsedExternalizer() {
        if (!USE_SHAREABLE_STUBS) {
            return new StubForwardIndexExternalizer.IdeStubForwardIndexesExternalizer();
        }
        return new FileLocalStubForwardIndexExternalizer();
    }

    @NonNull
    public static StubForwardIndexExternalizer<?> createFileLocalExternalizer() {
        return new FileLocalStubForwardIndexExternalizer();
    }

    /* ====================== abstract factory methods to override
    ====================================== */

    protected abstract StubKeySerializationState createStubIndexKeySerializationState(@NonNull DataOutput out,
                                                                                      @NonNull Set<StubIndexKey<?, ?>> set) throws IOException;

    protected abstract void writeStubIndexKey(@NonNull DataOutput out,
                                              @NonNull StubIndexKey key,
                                              StubKeySerializationState state) throws IOException;

    protected abstract StubKeySerializationState createStubIndexKeySerializationState(@NonNull DataInput input,
                                                                                      int stubIndexKeyCount) throws IOException;

    protected abstract ID<?, ?> readStubIndexKey(@NonNull DataInput input,
                                                 StubKeySerializationState stubKeySerializationState) throws IOException;

    /* ====================== public API methods
    ======================================================== */
    @Override
    public final void save(@NonNull DataOutput out,
                           @NonNull Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> indexedStubs) throws IOException {
        DataInputOutputUtil.writeINT(out, indexedStubs.size());
        if (!indexedStubs.isEmpty()) {
            final StubKeySerializationState stubKeySerializationState =
                    createStubIndexKeySerializationState(out, indexedStubs.keySet());

            final Iterable<StubIndexKey<?, ?>> keysToStore =
                    useStableBinaryFormat ? indexedStubs.keySet()
                            .stream()
                            .sorted(Comparator.comparing(StubIndexKey::getName))
                            .collect(Collectors.toList()) : indexedStubs.keySet();
            for (StubIndexKey stubIndexKey : keysToStore) {
                writeStubIndexKey(out, stubIndexKey, stubKeySerializationState);
                Map<Object, StubIdList> map = indexedStubs.get(stubIndexKey);
                serializeIndexValue(out, stubIndexKey, map);
            }
        }
    }

    @Override
    public final Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> read(@NonNull DataInput in) throws IOException {
        return doRead(in, null, null);
    }

    /* ====================== implementation
    =========================================================== */

    protected <K> Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> doRead(@NonNull DataInput in,
                                                                          @Nullable StubIndexKey<K, ?> requestedIndex,
                                                                          @Nullable K requestedKey) throws IOException {
        int stubIndicesValueMapSize = DataInputOutputUtil.readINT(in);
        if (stubIndicesValueMapSize > 0) {
            Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> stubIndicesValueMap =
                    requestedIndex != null ? null : new HashMap<>(stubIndicesValueMapSize);
            StubKeySerializationState stubKeySerializationState =
                    createStubIndexKeySerializationState(in, stubIndicesValueMapSize);
            for (int i = 0; i < stubIndicesValueMapSize; ++i) {
                ID<Object, ?> indexKey =
                        (ID<Object, ?>) readStubIndexKey(in, stubKeySerializationState);
                if (indexKey instanceof StubIndexKey) { // indexKey can be ID in case of removed
                    // index
                    StubIndexKey<Object, ?> stubIndexKey = (StubIndexKey<Object, ?>) indexKey;
                    boolean deserialize =
                            requestedIndex == null || requestedIndex.equals(stubIndexKey);
                    if (deserialize) {
                        Map<Object, StubIdList> value =
                                deserializeIndexValue(in, stubIndexKey, requestedKey);
                        if (requestedIndex != null) {
                            return Collections.singletonMap(requestedIndex, value);
                        }
                        stubIndicesValueMap.put(stubIndexKey, value);
                    } else {
                        skipIndexValue(in);
                    }
                } else {
                    // key is deleted, just properly skip bytes (used while index update)
                    assert indexKey == null : "indexKey '" + indexKey + "' is not a StubIndexKey";
                    skipIndexValue(in);
                }
            }
            return stubIndicesValueMap;
        }
        return Collections.emptyMap();
    }

    private <K> void serializeIndexValue(final @NonNull DataOutput out,
                                         final @NonNull StubIndexKey<K, ?> stubIndexKey,
                                         final @NonNull Map<K, StubIdList> map) throws IOException {
        final KeyDescriptor<K> keyDescriptor =
                StubIndexKeyDescriptorCache.INSTANCE.getKeyDescriptor(stubIndexKey);

        final BufferExposingByteArrayOutputStream indexOs =
                new BufferExposingByteArrayOutputStream();
        try (DataOutputStream indexDos = new DataOutputStream(indexOs)) {
            //TODO RC: what if keys (type K) are not comparable?
            final Iterable<K> keysToStore =
                    useStableBinaryFormat ? map.keySet().stream().sorted().collect(Collectors.toList()) : map.keySet();
            for (K key : keysToStore) {
                keyDescriptor.save(indexDos, key);
                final StubIdList idsList = map.get(key);
                //TODO RC: check idsList really sorted (as its javadoc suggests)?
                StubIdExternalizer.INSTANCE.save(indexDos, idsList);
            }
            DataInputOutputUtil.writeINT(out, indexDos.size());
        }
        out.write(indexOs.getInternalBuffer(), 0, indexOs.size());
    }

    @NonNull
    private <K> Map<K, StubIdList> deserializeIndexValue(@NonNull DataInput in,
                                                         @NonNull StubIndexKey<K, ?> stubIndexKey,
                                                         @Nullable K requestedKey) throws IOException {
        KeyDescriptor<K> keyDescriptor =
                StubIndexKeyDescriptorCache.INSTANCE.getKeyDescriptor(stubIndexKey);

        int bufferSize = DataInputOutputUtil.readINT(in);
        byte[] buffer = new byte[bufferSize];
        in.readFully(buffer);
        UnsyncByteArrayInputStream indexIs = new UnsyncByteArrayInputStream(buffer);
        DataInputStream indexDis = new DataInputStream(indexIs);
        HashingStrategy<K> hashingStrategy =
                StubIndexKeyDescriptorCache.INSTANCE.getKeyHashingStrategy(stubIndexKey);
        Map<K, StubIdList> result =
                CollectionFactory.createCustomHashingStrategyMap(hashingStrategy);
        while (indexDis.available() > 0) {
            K key = keyDescriptor.read(indexDis);
            StubIdList read = StubIdExternalizer.INSTANCE.read(indexDis);
            if (requestedKey == null) {
                result.put(key, read);
            } else if (hashingStrategy.equals(requestedKey, key)) {
                result.put(key, read);
                return result;
            }
        }
        return result;
    }

    private void skipIndexValue(@NonNull DataInput in) throws IOException {
        int bufferSize = DataInputOutputUtil.readINT(in);
        in.skipBytes(bufferSize);
    }

    private static final class IdeStubForwardIndexesExternalizer extends StubForwardIndexExternalizer<Void> {
        private IdeStubForwardIndexesExternalizer() {
        }

        @Override
        protected void writeStubIndexKey(@NonNull DataOutput out,
                                         @NonNull StubIndexKey key,
                                         Void aVoid) throws IOException {
            DataInputOutputUtil.writeINT(out, key.getUniqueId());
        }

        @Override
        protected Void createStubIndexKeySerializationState(@NonNull DataOutput out,
                                                            @NonNull Set<StubIndexKey<?, ?>> set) {
            return null;
        }

        @Override
        protected ID<?, ?> readStubIndexKey(@NonNull DataInput input,
                                            Void aVoid) throws IOException {
            return ID.findById(DataInputOutputUtil.readINT(input));
        }

        @Override
        protected Void createStubIndexKeySerializationState(@NonNull DataInput input,
                                                            int stubIndexKeyCount) {
            return null;
        }
    }

    private static final class FileLocalStubForwardIndexExternalizer extends StubForwardIndexExternalizer<FileLocalStringEnumerator> {
        private FileLocalStubForwardIndexExternalizer() {
        }

        @Override
        protected FileLocalStringEnumerator createStubIndexKeySerializationState(@NonNull DataOutput out,
                                                                                 @NonNull Set<StubIndexKey<?, ?>> set) throws IOException {
            FileLocalStringEnumerator enumerator = new FileLocalStringEnumerator(true);
            for (StubIndexKey<?, ?> key : set) {
                enumerator.enumerate(key.getName());
            }
            enumerator.write(out);
            return enumerator;
        }

        @Override
        protected void writeStubIndexKey(@NonNull DataOutput out,
                                         @NonNull StubIndexKey key,
                                         FileLocalStringEnumerator enumerator) throws IOException {
            DataInputOutputUtil.writeINT(out, enumerator.enumerate(key.getName()));
        }

        @Override
        protected FileLocalStringEnumerator createStubIndexKeySerializationState(@NonNull DataInput input,
                                                                                 int stubIndexKeyCount) throws IOException {
            FileLocalStringEnumerator enumerator = new FileLocalStringEnumerator(false);
            enumerator.read(input, UnaryOperator.identity());
            return enumerator;
        }

        @Override
        protected ID<?, ?> readStubIndexKey(@NonNull DataInput input,
                                            FileLocalStringEnumerator enumerator) throws IOException {
            int idx = DataInputOutputUtil.readINT(input);
            String name = enumerator.valueOf(idx);
            if (name == null) {
                throw new IOException("corrupted data: no value for idx = " +
                                      idx +
                                      " in local enumerator");
            }
            return ID.findByName(name);
        }
    }
}