package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.com.intellij.util.io.PageCacheUtils;
import org.jetbrains.kotlin.com.intellij.util.io.ResizeableMappedFile;
import org.jetbrains.kotlin.com.intellij.util.io.StorageLockContext;

import java.io.IOException;
import java.nio.file.Path;

public class PersistentFSRecordsStorageFactory {
    private static final StorageLockContext PERSISTENT_FS_STORAGE_CONTEXT_RW =
            new StorageLockContext(true, true, true);

    public static PersistentFSRecordsStorage createStorage(Path file) throws IOException {
        FSRecords.LOG.info("using NORMAL storage for VFS records");
        return new PersistentFSSynchronizedRecordsStorage(openRMappedFile(file,
                PersistentFSSynchronizedRecordsStorage.RECORD_SIZE));
    }

    private static ResizeableMappedFile openRMappedFile(Path file, int recordLength) throws IOException {
        int pageSize = PageCacheUtils.DEFAULT_PAGE_SIZE * recordLength /
                       PersistentFSSynchronizedRecordsStorage.RECORD_SIZE;
        boolean aligned = pageSize % recordLength == 0;
        if (!aligned) {
            String message = "Record length(=" +
                             recordLength +
                             ") is not aligned with page size(=" +
                             pageSize +
                             ")";
            Logger.getInstance(PersistentFSRecordsStorage.class).error(message);
        }
        return new ResizeableMappedFile(file,
                recordLength * 1024,
                PERSISTENT_FS_STORAGE_CONTEXT_RW,
                pageSize,
                aligned,
                IOUtil.useNativeByteOrderForByteBuffers());
    }
}
