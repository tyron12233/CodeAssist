package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AttributeInputStream;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.FileAttribute;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.io.ClosedStorageException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class FSRecords {

    public static final boolean useContentHashes = SystemProperties.getBooleanProperty("idea.share.contents", true);

    /**
     * Initially record=0 was used as a storage header record, hence fileId=0 was reserved.
     * New storages still reserve id=0, even though they usually separate the header from
     * records explicitly -- because it is consistent with 0 being used as NULL in other parts
     * of app, e.g. in DataEnumerator
     */
    public static final int NULL_FILE_ID = 0;
    static final boolean backgroundVfsFlush = SystemProperties.getBooleanProperty("idea.background.vfs.flush", true);
    static final boolean inlineAttributes = SystemProperties.getBooleanProperty("idea.inline.vfs.attributes", true);

    /**
     * If true, enhance each attribute record with backref to fileId owned this attribute(s).
     *
     * @deprecated This is likely unfinished work since this backref is never used
     */
    @Deprecated
    static final boolean bulkAttrReadSupport = SystemProperties.getBooleanProperty("idea.bulk.attr.read", false);

    /**
     * If true -> use {@link CompactRecordsTable} for managing attributes record, instead of default {@link com.intellij.util.io.storage.RecordsTable}
     */
    static final boolean useSmallAttrTable = SystemProperties.getBooleanProperty("idea.use.small.attr.table.for.vfs", true);

    public static final String IDE_USE_FS_ROOTS_DATA_LOADER = "idea.fs.roots.data.loader";
    static @NotNull Logger LOG = Logger.getInstance(FSRecords.class);
    private static PersistentFSConnection ourConnection;
    private static PersistentFSAttributeAccessor ourAttributeAccessor;

    private FSRecords() {
        throw new AssertionError("Not for instantiation");
    }

    /** @return path to the directory there all VFS files are located */
    public static @NotNull String getCachesDir() {
        String dir = System.getProperty("caches_dir");
        return dir == null ? PathManager.getSystemPath() + "/caches/" : dir;
    }

    @NotNull
    private static PersistentFSConnection getConnectionOrFail() {
        PersistentFSConnection connection = ourConnection;
        if (connection == null) {
            throw new RuntimeException("VFS is already disposed");
        }
        return connection;
    }

    public static synchronized void flush() {
        if (ourConnection != null) {
            try {
                ourConnection.doForce();
            } catch (IOException e) {
                ourConnection.handleError(e);
            }
        }
    }

    public static synchronized void dispose() {
        if (ourConnection != null) {
            PersistentFSConnector.disconnect(ourConnection);

            ourConnection = null;
//            ourContentAccessor = null;
            ourAttributeAccessor = null;
//            ourTreeAccessor = null;
//            ourRecordAccessor = null;
        }
    }

    public static void connect() {
        ourConnection = PersistentFSConnector.connect(getCachesDir(), 1, true);
        ourAttributeAccessor = new PersistentFSAttributeAccessor(ourConnection);
    }

    public static int createRecord() {
        try {
            return getConnectionOrFail().getRecords().allocateRecord();
        }
        catch (Exception e) {
            throw handleError(e);
        }
    }

    public static PersistentFSRecordsStorage getRecords() {
        return getConnectionOrFail().getRecords();
    }

    public static long getCreationTimestamp() {
        return getConnectionOrFail().getTimestamp();
    }

    public static DataInputStream readAttributeWithLock(int fileId, FileAttribute attribute) {
        try {
            return readAttribute(fileId, attribute);
        }
        catch (Throwable e) {
            throw handleError(e);
        }
    }

    /** must be called under r or w lock */
    private static @Nullable AttributeInputStream readAttribute(int fileId, @NotNull FileAttribute attribute) throws IOException {
        return ourAttributeAccessor.readAttribute(fileId, attribute);
    }

    /**
     * Method is supposed to be called in a pattern like this:
     * <pre>
     * try{
     *  ...
     * }
     * catch(Throwable t){
     *   throw handeError(e);
     * }
     * </pre>
     * i.e. in a 'throw' statement -- to make clear, it will throw an exception. Method made return
     * RuntimeException specifically for that purpose: to be used in a 'throw' statement, so compiler
     * understands it is as a method exit point.
     */
    @Contract("_->fail")
    public static RuntimeException handleError(final Throwable e) throws RuntimeException, Error {
        if (e instanceof ClosedStorageException) {
            // no connection means IDE is closing...
            throw new RuntimeException("VFS already disposed");
        }
        if (e instanceof ProcessCanceledException) {
            throw (ProcessCanceledException)e;
        }
        if (ourConnection != null) {
            ourConnection.handleError(e);
        }
        // no connection means IDE is closing...
        throw new RuntimeException();
    }

    public static DataOutputStream writeAttribute(int fileId, FileAttribute versionStamp) {
        return ourAttributeAccessor.writeAttribute(fileId, versionStamp);
    }
}
