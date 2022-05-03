package com.tyron.builder.api.internal.changedetection.state;

import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicLong;

public interface FileHasherStatistics {
    /**
     * Number of files hashed.
     */
    long getHashedFileCount();

    /**
     * Amount of bytes hashed.
     */
    long getHashedContentLength();

    class Collector {
        private final AtomicLong hashedFileCount = new AtomicLong();
        private final AtomicLong hashedContentLength = new AtomicLong();

        public void reportFileHashed(long length) {
            hashedFileCount.incrementAndGet();
            hashedContentLength.addAndGet(length);
        }

        public FileHasherStatistics collect() {
            long hashedFileCount = this.hashedFileCount.getAndSet(0);
            long hashedContentLength = this.hashedContentLength.getAndSet(0);
            return new FileHasherStatistics() {
                @Override
                public long getHashedFileCount() {
                    return hashedFileCount;
                }

                @Override
                public long getHashedContentLength() {
                    return hashedContentLength;
                }

                @Override
                public String toString() {
                    return MessageFormat.format("Hashed {0,number,integer} files ({1,number,integer} bytes)",
                        hashedFileCount, hashedContentLength
                    );
                }
            };
        }
    }
}
