package com.tyron.builder.cache.internal.filelock;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class LockInfoSerializer {
    public static final int INFORMATION_REGION_DESCR_CHUNK_LIMIT = 340;

    public byte getVersion() {
        return 3;
    }

    public void write(DataOutput dataOutput, LockInfo lockInfo) throws IOException {
        dataOutput.writeInt(lockInfo.port);
        dataOutput.writeLong(lockInfo.lockId);
        dataOutput.writeUTF(trimIfNecessary(lockInfo.pid));
        dataOutput.writeUTF(trimIfNecessary(lockInfo.operation));
    }

    public LockInfo read(DataInput dataInput) throws IOException {
        LockInfo out = new LockInfo();
        out.port = dataInput.readInt();
        out.lockId = dataInput.readLong();
        out.pid = dataInput.readUTF();
        out.operation = dataInput.readUTF();
        return out;
    }

    private String trimIfNecessary(String inputString) {
        if (inputString.length() > INFORMATION_REGION_DESCR_CHUNK_LIMIT) {
            return inputString.substring(0, INFORMATION_REGION_DESCR_CHUNK_LIMIT);
        } else {
            return inputString;
        }
    }
}