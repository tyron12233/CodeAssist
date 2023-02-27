package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FileAttribute {
  private static final Set<String> ourRegisteredIds = Collections.synchronizedSet(new HashSet<>());
  private static final int UNDEFINED_VERSION = -1;
  private final String myId;
  private final int myVersion;
  /**
   * Indicates that attribute content ({@link #writeAttributeBytes(VirtualFile, byte[])}) are of fixed size.
   * This serves as a hint for storage allocation: for fixed-size attributes space could be allocated
   * without reserve for future extension.
   */
  private final boolean myFixedSize;
  /**
   * Intended for enumeration of all binary data, but not used/implemented for today
   */
  private final boolean myShouldEnumerate;

  public FileAttribute(@NonNull String id) {
    this(id, UNDEFINED_VERSION, false, false);
  }

  public FileAttribute(@NonNull String id, int version, boolean fixedSize) {
    this(id, version, fixedSize, false);
  }

  public FileAttribute(@NonNull String id, int version, boolean fixedSize, boolean shouldEnumerate) {
    this(version, fixedSize, id, shouldEnumerate);
    boolean added = ourRegisteredIds.add(id);
    assert added : "Attribute id='" + id+ "' is not unique";
  }

  private FileAttribute(int version, boolean fixedSize,@NonNull String id, boolean shouldEnumerate) {
    myId = id;
    myVersion = version;
    myFixedSize = fixedSize;
    // TODO enumerate all binary data if asked
    myShouldEnumerate = shouldEnumerate;
  }

  /**
   * @deprecated use {@link FileAttribute#readFileAttribute(VirtualFile)}
   */
  @Deprecated
  @Nullable
  public DataInputStream readAttribute(@NonNull VirtualFile file) {
    return ManagingFS.getInstance().readAttribute(file, this);
  }

  /**
   * @deprecated use {@link FileAttribute#writeFileAttribute(VirtualFile)}
   */
  @Deprecated
  @NonNull
  public DataOutputStream writeAttribute(@NonNull VirtualFile file) {
    return ManagingFS.getInstance().writeAttribute(file, this);
  }

  @Nullable
  public AttributeInputStream readFileAttribute(@NonNull VirtualFile file) {
    return ManagingFS.getInstance().readAttribute(file, this);
  }

  @NonNull
  public AttributeOutputStream writeFileAttribute(@NonNull VirtualFile file) {
    return ManagingFS.getInstance().writeAttribute(file, this);
  }


  public byte[] readAttributeBytes(VirtualFile file) throws IOException {
    try (DataInputStream stream = readAttribute(file)) {
        if (stream == null) {
            return null;
        }
      int len = stream.readInt();
      return FileUtil.loadBytes(stream, len);
    }
  }

  public void writeAttributeBytes(VirtualFile file, byte[] bytes) throws IOException {
    writeAttributeBytes(file, bytes, 0, bytes.length);
  }

  public void writeAttributeBytes(VirtualFile file, byte[] bytes, int offset, int len) throws IOException {
    try (DataOutputStream stream = writeAttribute(file)) {
      stream.writeInt(len);
      stream.write(bytes, offset, len);
    }
  }

  @NonNull
  public String getId() {
    return myId;
  }

  public boolean isFixedSize() {
    return myFixedSize;
  }

  @NonNull
  public FileAttribute newVersion(int newVersion) {
    return new FileAttribute(newVersion, myFixedSize, myId, myShouldEnumerate);
  }

  public int getVersion() {
    return myVersion;
  }

  public boolean isVersioned() {
    return myVersion != UNDEFINED_VERSION;
  }

  public static void resetRegisteredIds() {
    ourRegisteredIds.clear();
  }

  @Override
  public String toString() {
    return "FileAttribute[" + myId + "]{" +
           ", version: " + myVersion +
           ", fixedSize: " + myFixedSize +
           ", shouldEnumerate: " + myShouldEnumerate +
           '}';
  }
}