package com.tyron.builder.cache.internal.filelock;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public interface LockStateSerializer {

    /**
     * size (bytes) of the data of this protocol.
     */
    int getSize();

    /**
     * single byte that describes the version.
     * an implementation protocol should increment the value when protocol changes in an incompatible way
     */
    byte getVersion();

    /**
     * Returns the initial state for a lock file with this format.
     */
    LockState createInitialState();

    /**
     * writes the state data
     */
    void write(DataOutput lockFileAccess, LockState lockState) throws IOException;

    /**
     * reads the state data
     */
    LockState read(DataInput lockFileAccess) throws IOException;
}