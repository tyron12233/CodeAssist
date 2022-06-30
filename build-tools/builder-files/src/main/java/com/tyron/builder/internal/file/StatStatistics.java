package com.tyron.builder.internal.file;

import java.io.File;
import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicLong;

public interface StatStatistics {
    /**
     * Number of times {@link Stat#stat(File)} was called.
     */
    long getStatCount();

    /**
     * Number of times {@link Stat#getUnixMode(File)} was called.
     */
    long getUnixModeCount();

    class Collector {
        private final AtomicLong statCount = new AtomicLong();
        private final AtomicLong unixModeCount = new AtomicLong();

        public void reportFileStated() {
            statCount.incrementAndGet();
        }

        public void reportUnixModeQueried() {
            unixModeCount.incrementAndGet();
        }

        public StatStatistics collect() {
            final long unixModeCount = this.unixModeCount.getAndSet(0);
            final long statCount = this.statCount.getAndSet(0);

            return new StatStatistics() {
                @Override
                public long getStatCount() {
                    return statCount;
                }

                @Override
                public long getUnixModeCount() {
                    return unixModeCount;
                }

                @Override
                public String toString() {
                    return MessageFormat.format("Executed stat() x {0,number,integer}. getUnixMode() x {1,number,integer}",
                        statCount, unixModeCount);
                }
            };
        }
    }
}
