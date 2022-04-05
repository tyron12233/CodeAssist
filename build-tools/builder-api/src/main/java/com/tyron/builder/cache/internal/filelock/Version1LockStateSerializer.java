package com.tyron.builder.cache.internal.filelock;

import com.tyron.builder.cache.FileLock;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * An older, cross-version state info format.
 */
public class Version1LockStateSerializer implements LockStateSerializer {
    @Override
    public int getSize() {
        return 1;
    }

    @Override
    public byte getVersion() {
        return 1;
    }

    @Override
    public LockState createInitialState() {
        return new DirtyFlagLockState(true);
    }

    @Override
    public void write(DataOutput dataOutput, LockState lockState) throws IOException {
        DirtyFlagLockState state = (DirtyFlagLockState) lockState;
        dataOutput.writeBoolean(!state.dirty);
    }

    @Override
    public LockState read(DataInput dataInput) throws IOException {
        return new DirtyFlagLockState(!dataInput.readBoolean());
    }

    private static class DirtyFlagLockState implements LockState {
        private final boolean dirty;

        private DirtyFlagLockState(boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public boolean isDirty() {
            return dirty;
        }

        @Override
        public boolean canDetectChanges() {
            return false;
        }

        @Override
        public boolean isInInitialState() {
            return false;
        }

        @Override
        public LockState beforeUpdate() {
            return new DirtyFlagLockState(true);
        }

        @Override
        public LockState completeUpdate() {
            return new DirtyFlagLockState(false);
        }

        @Override
        public boolean hasBeenUpdatedSince(FileLock.State state) {
            throw new UnsupportedOperationException("This protocol version does not support detecting changes by other processes.");
        }
    }
}