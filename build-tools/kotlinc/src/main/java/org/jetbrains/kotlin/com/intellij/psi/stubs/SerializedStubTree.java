package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.HashingStrategy;
import org.jetbrains.kotlin.com.intellij.util.io.DigestUtil;
import org.jetbrains.kotlin.com.intellij.util.io.UnsyncByteArrayInputStream;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import gnu.trove.TObjectHashingStrategy;

//@ApiStatus.Internal
public final class SerializedStubTree {
  // serialized tree
  final byte[] myTreeBytes;
  final int myTreeByteLength;

  // stub forward indexes
  final byte[] myIndexedStubBytes;
  final int myIndexedStubByteLength;
  private Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> myIndexedStubs;

  private final @NonNull StubTreeSerializer mySerializationManager;
  private final @NonNull StubForwardIndexExternalizer<?> myStubIndexesExternalizer;

  public SerializedStubTree(byte[] treeBytes,
                            int treeByteLength,
                            byte[] indexedStubBytes,
                            int indexedStubByteLength,
                            @Nullable Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> indexedStubs,
                            @NonNull StubForwardIndexExternalizer<?> stubIndexesExternalizer,
                            @NonNull StubTreeSerializer serializationManager) {
    myTreeBytes = treeBytes;
    myTreeByteLength = treeByteLength;
    myIndexedStubBytes = indexedStubBytes;
    myIndexedStubByteLength = indexedStubByteLength;
    myIndexedStubs = indexedStubs;
    myStubIndexesExternalizer = stubIndexesExternalizer;
    mySerializationManager = serializationManager;
  }

  public static @NonNull SerializedStubTree serializeStub(@NonNull Stub rootStub,
                                                          @NonNull StubTreeSerializer serializationManager,
                                                          @NonNull StubForwardIndexExternalizer<?> forwardIndexExternalizer) throws IOException {
    final BufferExposingByteArrayOutputStream bytes = new BufferExposingByteArrayOutputStream();
    serializationManager.serialize(rootStub, bytes);
    byte[] treeBytes = bytes.getInternalBuffer();
    int treeByteLength = bytes.size();
    ObjectStubBase<?> root = (ObjectStubBase)rootStub;
    Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> indexedStubs = indexTree(root);
    final BufferExposingByteArrayOutputStream indexBytes = new BufferExposingByteArrayOutputStream();
    forwardIndexExternalizer.save(new DataOutputStream(indexBytes), indexedStubs);
    byte[] indexedStubBytes = indexBytes.getInternalBuffer();
    int indexedStubByteLength = indexBytes.size();
    return new SerializedStubTree(
      treeBytes,
      treeByteLength,
      indexedStubBytes,
      indexedStubByteLength,
      indexedStubs,
      forwardIndexExternalizer,
      serializationManager
    );
  }

  public @NonNull SerializedStubTree reSerialize(@NonNull StubTreeSerializer newSerializationManager,
                                                 @NonNull StubForwardIndexExternalizer<?> newForwardIndexSerializer) throws IOException {
    BufferExposingByteArrayOutputStream outStub = new BufferExposingByteArrayOutputStream();
    ((SerializationManagerEx)mySerializationManager).reSerialize(new ByteArrayInputStream(myTreeBytes, 0, myTreeByteLength), outStub, newSerializationManager);

    byte[] reSerializedIndexBytes;
    int reSerializedIndexByteLength;

    if (myStubIndexesExternalizer == newForwardIndexSerializer) {
      reSerializedIndexBytes = myIndexedStubBytes;
      reSerializedIndexByteLength = myIndexedStubByteLength;
    }
    else {
      BufferExposingByteArrayOutputStream reSerializedStubIndices = new BufferExposingByteArrayOutputStream();
      newForwardIndexSerializer.save(new DataOutputStream(reSerializedStubIndices), getStubIndicesValueMap());
      reSerializedIndexBytes = reSerializedStubIndices.getInternalBuffer();
      reSerializedIndexByteLength = reSerializedStubIndices.size();
    }

    return new SerializedStubTree(
      outStub.getInternalBuffer(),
      outStub.size(),
      reSerializedIndexBytes,
      reSerializedIndexByteLength,
      myIndexedStubs,
      newForwardIndexSerializer,
      newSerializationManager
    );
  }

//  @ApiStatus.Internal
  @NonNull
  public StubForwardIndexExternalizer<?> getStubIndexesExternalizer() {
    return myStubIndexesExternalizer;
  }

  void restoreIndexedStubs() throws IOException {
    if (myIndexedStubs == null) {
      myIndexedStubs = myStubIndexesExternalizer.read(new DataInputStream(new ByteArrayInputStream(myIndexedStubBytes, 0, myIndexedStubByteLength)));
    }
  }

  <K> StubIdList restoreIndexedStubs(@NonNull StubIndexKey<K, ?> indexKey, @NonNull K key) throws IOException {
    Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> incompleteMap = myStubIndexesExternalizer.doRead(new DataInputStream(new ByteArrayInputStream(myIndexedStubBytes, 0, myIndexedStubByteLength)), indexKey, key);
      if (incompleteMap == null) {
          return null;
      }
    Map<Object, StubIdList> map = incompleteMap.get(indexKey);
    return map == null ? null : map.get(key);
  }

  public @NonNull Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> getStubIndicesValueMap() {
    try {
      restoreIndexedStubs();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return myIndexedStubs;
  }

  public @NonNull Stub getStub() throws SerializerNotFoundException {
    if (myTreeByteLength == 0) {
      return NO_STUB;
    }
    return mySerializationManager.deserialize(new UnsyncByteArrayInputStream(myTreeBytes, 0, myTreeByteLength));
  }

  public @NonNull SerializedStubTree withoutStub() {
    return new SerializedStubTree(ArrayUtil.EMPTY_BYTE_ARRAY,
                                  0,
                                  myIndexedStubBytes,
                                  myIndexedStubByteLength,
                                  myIndexedStubs,
                                  myStubIndexesExternalizer,
                                  mySerializationManager);
  }

  @Override
  public boolean equals(final Object that) {
    if (this == that) {
      return true;
    }
    if (!(that instanceof SerializedStubTree)) {
      return false;
    }

    SerializedStubTree thatTree = (SerializedStubTree) that;

    final int length = myTreeByteLength;
    if (length != thatTree.myTreeByteLength) {
      return false;
    }

    for (int i = 0; i < length; i++) {
      if (myTreeBytes[i] != thatTree.myTreeBytes[i]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public int hashCode() {

    int result = 1;
    for (int i = 0; i < myTreeByteLength; i++) {
      result = 31 * result + myTreeBytes[i];
    }

    return result;
  }

  static @NonNull Map<StubIndexKey<?, ?>, Map<Object, StubIdList>> indexTree(@NonNull Stub root) {
    ObjectStubTree<?> objectStubTree = root instanceof PsiFileStub
                                       ? new StubTree((PsiFileStub)root, false)
                                       : new ObjectStubTree<>((ObjectStubBase<?>)root, false);
    Map<StubIndexKey<?, ?>, Map<Object, int[]>> map = objectStubTree.indexStubTree(k -> {
      //noinspection unchecked

      HashingStrategy<Object> keyHashingStrategy =
              StubIndexKeyDescriptorCache.INSTANCE.getKeyHashingStrategy((StubIndexKey<Object, ?>) k);
      return new TObjectHashingStrategy<Object>() {
        @Override
        public int computeHashCode(Object o) {
          return keyHashingStrategy.hashCode(o);
        }

        @Override
        public boolean equals(Object o, Object t1) {
          return keyHashingStrategy.equals(o, t1);
        }
      };
    });

    // xxx:fix refs inplace
    for (StubIndexKey key : map.keySet()) {
      Map<Object, int[]> value = map.get(key);
      for (Object k : value.keySet()) {
        int[] ints = value.get(k);
        StubIdList stubList = ints.length == 1 ? new StubIdList(ints[0]) : new StubIdList(ints, ints.length);
        ((Map<Object, StubIdList>)(Map)value).put(k, stubList);
      }
    }
    return (Map<StubIndexKey<?, ?>, Map<Object, StubIdList>>)(Map)map;
  }

  private byte[] myTreeHash;
  public synchronized byte[] getTreeHash() {
    if (myTreeHash == null) {
      // Probably we don't need to hash the length and "\0000".
      MessageDigest digest = DigestUtil.sha256();
      digest.update(String.valueOf(myTreeByteLength).getBytes(StandardCharsets.UTF_8));
      digest.update("\u0000".getBytes(StandardCharsets.UTF_8));
      digest.update(myTreeBytes, 0, myTreeByteLength);
      myTreeHash = digest.digest();
    }
    return myTreeHash;
  }

  // TODO replace it with separate StubTreeLoader implementation
  public static final Stub NO_STUB = new Stub() {
    @Override
    public Stub getParentStub() {
      return null;
    }

    @Override
    public @NonNull List<? extends Stub> getChildrenStubs() {
      return Collections.emptyList();
    }

    @Override
    public ObjectStubSerializer<?, ?> getStubType() {
      return null;
    }

    @Override
    public String toString() {
      return "<no stub>";
    }
  };
}