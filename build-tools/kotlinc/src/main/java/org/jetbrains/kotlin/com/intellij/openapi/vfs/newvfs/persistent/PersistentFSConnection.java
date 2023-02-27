package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.Forceable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.io.SimpleStringPersistentEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.storage.CapacityAllocationPolicy;

import java.io.IOException;

public class PersistentFSConnection {

    private static final Logger LOG = Logger.getInstance(PersistentFSConnection.class);

    static final int RESERVED_ATTR_ID = FSRecords.bulkAttrReadSupport ? 1 : 0;
    static final AttrPageAwareCapacityAllocationPolicy REASONABLY_SMALL =
            new AttrPageAwareCapacityAllocationPolicy();
    private static final int FIRST_ATTR_ID_OFFSET =
            FSRecords.bulkAttrReadSupport ? RESERVED_ATTR_ID : 0;

    private static final boolean USE_GENTLE_FLUSHER =
            SystemProperties.getBooleanProperty("vfs.flushing.use-gentle-flusher", true);
    private final PersistentFSRecordsStorage myRecords;
    private final AbstractAttributesStorage myAttributesStorage;

    @NotNull
    private final VfsDependentEnum myAttributesList;
    private final PersistentFSPaths myPersistentFSPaths;
    private SimpleStringPersistentEnumerator myEnumeratedAttributes;


    PersistentFSConnection(PersistentFSRecordsStorage records,

                           @NotNull AbstractAttributesStorage attributes,
                           PersistentFSPaths paths,
                           @NotNull SimpleStringPersistentEnumerator enumeratedAttributes) {
        myRecords = records;
        myAttributesStorage = attributes;
        myPersistentFSPaths = paths;
        myEnumeratedAttributes = enumeratedAttributes;
        myAttributesList = new VfsDependentEnum(getPersistentFSPaths(), "attrib", 1);
    }

    /**
     * @param id - file id, name id, any other positive id
     */
    static void ensureIdIsValid(int id) {
        assert id > 0 : id;
    }

    SimpleStringPersistentEnumerator getEnumeratedAttributes() {
        return myEnumeratedAttributes;
    }

    @NotNull PersistentFSPaths getPersistentFSPaths() {
        return myPersistentFSPaths;
    }

    AbstractAttributesStorage getAttributes() {
        return myAttributesStorage;
    }

    int getAttributeId(@NotNull String attId) throws IOException {
        // do not invoke FSRecords.requestVfsRebuild under read lock to avoid deadlock
        return myAttributesList.getIdRaw(attId) + FIRST_ATTR_ID_OFFSET;
    }

    void handleError(@NotNull Throwable e) throws RuntimeException, Error {
        ExceptionUtil.rethrow(e);
    }

    public long getTimestamp() {
        return 0;
    }

    PersistentFSRecordsStorage getRecords() {
        return myRecords;
    }

    void closeFiles() throws IOException {
//        if (flushingTask != null) {
//            flushingTask.close();
//        }

//        writeConnectionState();
//        closeStorages(myRecords,
//                myNames,
//                myAttributesStorage,
//                myContentHashesEnumerator,
//                myContents);

        if (myRecords != null) {
            myRecords.close();
        }

        if (myAttributesStorage != null) {
            myAttributesStorage.close();
        }
    }

    static class AttrPageAwareCapacityAllocationPolicy extends CapacityAllocationPolicy {
        boolean myAttrPageRequested;

        @Override
        public int calculateCapacity(int requiredLength) {   // 20% for growth
            return Math.max(myAttrPageRequested ? 8 : 32,
                    Math.min((int) (requiredLength * 1.2), (requiredLength / 1024 + 1) * 1024));
        }
    }

    void doForce() throws IOException {
//        // avoid NPE when close has already taken place
//        if (myNames != null && flushingTask != null) {
//            if (myNames instanceof Forceable) {
//                ((Forceable)myNames).force();
//            }
//            myAttributesStorage.force();
////            myContents.force();
////            if (myContentHashesEnumerator != null) myContentHashesEnumerator.force();
////            writeConnectionState();
//            myRecords.force();
//        }
    }
}
