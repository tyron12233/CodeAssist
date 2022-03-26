package com.tyron.builder.api.internal.time;

import com.google.common.annotations.VisibleForTesting;

@VisibleForTesting
interface TimeSource {

    long currentTimeMillis();

    long nanoTime();

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

}