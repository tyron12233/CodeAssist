package com.tyron.builder.api.internal.file.archive;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;
import com.tyron.builder.api.internal.file.archive.compression.ArchiveOutputStreamFactory;
import com.tyron.builder.api.internal.file.copy.CopyAction;
import com.tyron.builder.api.internal.file.copy.CopyActionProcessingStream;
import com.tyron.builder.api.internal.file.copy.FileCopyDetailsInternal;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.internal.ErroringAction;
import com.tyron.builder.internal.IoActions;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.apache.tools.zip.UnixStat;

import java.io.File;
import java.io.OutputStream;

public class TarCopyAction implements CopyAction {
    /**
     * An arbitrary timestamp chosen to provide constant file timestamps inside the tar archive.
     *
     * The value 0 is avoided to circumvent certain limitations of languages and applications that do not work well with the zero value.
     * (Like older Java implementations and libraries)
     *
     * The date is January 2, 1970.
     */
    public static final long CONSTANT_TIME_FOR_TAR_ENTRIES = 86400000;

    private final File tarFile;
    private final ArchiveOutputStreamFactory compressor;
    private final boolean preserveFileTimestamps;

    public TarCopyAction(File tarFile, ArchiveOutputStreamFactory compressor, boolean preserveFileTimestamps) {
        this.tarFile = tarFile;
        this.compressor = compressor;
        this.preserveFileTimestamps = preserveFileTimestamps;
    }

    @Override
    public WorkResult execute(final CopyActionProcessingStream stream) {

        final OutputStream outStr;
        try {
            outStr = compressor.createArchiveOutputStream(tarFile);
        } catch (Exception e) {
            throw new BuildException(String.format("Could not create TAR '%s'.", tarFile), e);
        }

        IoActions.withResource(outStr, new ErroringAction<OutputStream>() {
            @Override
            protected void doExecute(final OutputStream outStr) throws Exception {
                TarOutputStream tarOutStr;
                try {
                    tarOutStr = new TarOutputStream(outStr);
                } catch (Exception e) {
                    throw new BuildException(String.format("Could not create TAR '%s'.", tarFile), e);
                }
                tarOutStr.setLongFileMode(TarOutputStream.LONGFILE_GNU);
                tarOutStr.setBigNumberMode(TarOutputStream.BIGNUMBER_STAR);
                stream.process(new StreamAction(tarOutStr));
                tarOutStr.close();
            }
        });

        return WorkResults.didWork(true);
    }

    private class StreamAction implements CopyActionProcessingStreamAction {
        private final TarOutputStream tarOutStr;

        public StreamAction(TarOutputStream tarOutStr) {
            this.tarOutStr = tarOutStr;
        }

        @Override
        public void processFile(FileCopyDetailsInternal details) {
            if (details.isDirectory()) {
                visitDir(details);
            } else {
                visitFile(details);
            }
        }

        private void visitFile(FileCopyDetails fileDetails) {
            try {
                TarEntry archiveEntry = new TarEntry(fileDetails.getRelativePath().getPathString());
                archiveEntry.setModTime(getArchiveTimeFor(fileDetails));
                archiveEntry.setSize(fileDetails.getSize());
                archiveEntry.setMode(UnixStat.FILE_FLAG | fileDetails.getMode());
                tarOutStr.putNextEntry(archiveEntry);
                fileDetails.copyTo(tarOutStr);
                tarOutStr.closeEntry();
            } catch (Exception e) {
                throw new BuildException(String.format("Could not add %s to TAR '%s'.", fileDetails, tarFile), e);
            }
        }

        private void visitDir(FileCopyDetails dirDetails) {
            try {
                // Trailing slash on name indicates entry is a directory
                TarEntry archiveEntry = new TarEntry(dirDetails.getRelativePath().getPathString() + '/');
                archiveEntry.setModTime(getArchiveTimeFor(dirDetails));
                archiveEntry.setMode(UnixStat.DIR_FLAG | dirDetails.getMode());
                tarOutStr.putNextEntry(archiveEntry);
                tarOutStr.closeEntry();
            } catch (Exception e) {
                throw new BuildException(String.format("Could not add %s to TAR '%s'.", dirDetails, tarFile), e);
            }
        }
    }

    private long getArchiveTimeFor(FileCopyDetails details) {
        return preserveFileTimestamps ? details.getLastModified() : CONSTANT_TIME_FOR_TAR_ENTRIES;
    }
}
