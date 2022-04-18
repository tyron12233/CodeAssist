package com.tyron.builder.cache.internal.filelock;

import com.tyron.builder.cache.FileLock;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Random;

public class DefaultLockStateSerializer implements LockStateSerializer {

    @Override
    public int getSize() {
        return 16;
    }

    @Override
    public byte getVersion() {
        return 3;
    }

    @Override
    public LockState createInitialState() {
        long creationNumber = new Random().nextLong();
        return new SequenceNumberLockState(creationNumber, -1, 0);
    }

    @Override
    public void write(DataOutput dataOutput, LockState lockState) throws IOException {
        SequenceNumberLockState state = (SequenceNumberLockState) lockState;
        dataOutput.writeLong(state.creationNumber);
        dataOutput.writeLong(state.sequenceNumber);
    }

    @Override
    public LockState read(DataInput dataInput) throws IOException {
        long creationNumber = dataInput.readLong();
        long sequenceNumber = dataInput.readLong();
        return new SequenceNumberLockState(creationNumber, sequenceNumber, sequenceNumber);
    }

    private static class SequenceNumberLockState implements LockState {
        private final long creationNumber;
        private final long originalSequenceNumber;
        private final long sequenceNumber;

        private SequenceNumberLockState(long creationNumber, long originalSequenceNumber, long sequenceNumber) {
            this.creationNumber = creationNumber;
            this.originalSequenceNumber = originalSequenceNumber;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public String toString() {
            return String.format("[%s,%s,%s]", creationNumber, sequenceNumber, isDirty());
        }

        @Override
        public LockState beforeUpdate() {
            return new SequenceNumberLockState(creationNumber, originalSequenceNumber, 0);
        }

        @Override
        public LockState completeUpdate() {
            long newSequenceNumber;
            if (isInInitialState()) {
                newSequenceNumber = 1;
            } else {
                newSequenceNumber = originalSequenceNumber + 1;
            }
            return new SequenceNumberLockState(creationNumber, newSequenceNumber, newSequenceNumber);
        }

        @Override
        public boolean isDirty() {
            return sequenceNumber == 0 || sequenceNumber != originalSequenceNumber;
        }

        @Override
        public boolean canDetectChanges() {
            return true;
        }

        @Override
        public boolean isInInitialState() {
            return originalSequenceNumber <= 0;
        }

        @Override
        public boolean hasBeenUpdatedSince(FileLock.State state) {
            SequenceNumberLockState other = (SequenceNumberLockState) state;
            return sequenceNumber != other.sequenceNumber || creationNumber != other.creationNumber;
        }
    }
}